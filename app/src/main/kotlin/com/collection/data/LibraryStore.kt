package com.collection.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.collection.domain.Attachment
import com.collection.domain.Entry
import com.collection.domain.Library
import com.collection.domain.Tag
import com.collection.domain.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.collection.sync.*

/** Offline library and owned original files. Public operations are serialized and run off the UI thread. */
class LibraryStore(context: Context, private val syncActor: String = SyncIdentity.actor(context)) : SyncLocalStore {
    private val app = context.applicationContext
    private val helper = LibraryDatabase(app)
    private val mutex = DATABASE_LOCKS.getOrPut(app.getDatabasePath("library.db").absolutePath) { Mutex() }
    private val originals: File get() = File(app.filesDir, "originals").apply {
        if (!isDirectory && !mkdirs()) throw IOException("无法创建原始文件目录")
    }

    suspend fun load(): Library = io { loadLocked() }

    suspend fun loadTrash(): List<TrashedEntry> = io {
        helper.readableDatabase.rawQuery("SELECT id, title, deletedAt FROM entries WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TrashedEntry(cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
        }
    }

    suspend fun saveEntry(entry: Entry, preserveConcurrent: Boolean = false): Entry = mutate {
        require(entry.title.isNotBlank() || entry.summary.isNotBlank() || entry.body.isNotBlank() || entry.attachments.isNotEmpty()) {
            "请添加文字、标题或文件后再保存"
        }
        require(entry.attachments.map { it.id }.distinct().size == entry.attachments.size) { "附件标识重复" }
        entry.attachments.forEach { verifyAttachment(it) }
        val db = helper.writableDatabase
        db.transaction {
            db.rawQuery("SELECT 1 FROM purged_entries WHERE id=?", arrayOf(entry.id)).use {
                require(!it.moveToFirst()) { "该条目已永久清除" }
            }
            val validTags = allTags(db).map { it.id }.toSet()
            val previous = db.rawQuery("SELECT createdAt, updatedAt, deletedAt FROM entries WHERE id=?", arrayOf(entry.id)).use {
                if (!it.moveToFirst()) null else if (it.isNull(2)) it.getLong(0) to it.getLong(1) else {
                    require(preserveConcurrent) { "条目已移到回收站，请先恢复" }
                    null
                }
            }
            val stale = preserveConcurrent && previous?.second != entry.updatedAt
            require(stale || validTags.containsAll(entry.tagIds)) { "标签已变更，请重新选择标签" }
            val edited = if (stale) entry.copy(id = newId(), title = entry.title + "（冲突副本）", tagIds = entry.tagIds.intersect(validTags)) else entry
            val updatedAt = maxOf(System.currentTimeMillis(), (previous?.second ?: 0L).coerceAtMost(Long.MAX_VALUE - 1) + 1)
            edited.copy(createdAt = previous?.first ?: entry.createdAt, updatedAt = updatedAt).also { putEntry(db, it) }
        }
    }

    suspend fun deleteEntry(id: String) = deleteEntries(setOf(id))

    suspend fun deleteEntries(ids: Set<String>) = mutate {
        val db = helper.writableDatabase
        val deletedAt = ContentValues().apply { put("deletedAt", System.currentTimeMillis()) }
        db.transaction {
            ids.sumOf { db.update("entries", deletedAt, "id=? AND deletedAt IS NULL", arrayOf(it)) }
        }
    }

    suspend fun restoreEntries(ids: Set<String>) = mutate {
        val db = helper.writableDatabase
        val restored = ContentValues().apply { putNull("deletedAt"); put("updatedAt", System.currentTimeMillis()) }
        db.transaction {
            ids.sumOf { db.update("entries", restored, "id=? AND deletedAt IS NOT NULL", arrayOf(it)) }
        }
    }

    suspend fun purgeEntries(ids: Set<String>, keepDraft: List<Attachment> = emptyList()): Int = io {
        purgeLocked(ids, keepDraft).also { if (it > 0) SyncSignals.localChanged() }
    }

    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int = io {
        val db = helper.writableDatabase
        val ids = db.rawQuery("SELECT id FROM entries WHERE deletedAt <= ?", arrayOf((now - TRASH_RETENTION_MILLIS).toString())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        purgeLocked(ids, emptyList()).also { if (it > 0) SyncSignals.localChanged() }
    }

    private fun purgeLocked(ids: Set<String>, keepDraft: List<Attachment>): Int {
        if (ids.isEmpty()) return 0
        val db = helper.writableDatabase
        val candidates = mutableSetOf<File>()
        val removed = db.transaction {
            ids.sumOf { id ->
                val args = arrayOf(id)
                db.rawQuery("SELECT attachments FROM entries WHERE id=? AND deletedAt IS NOT NULL", args).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val files = readAttachments(JSONArray(cursor.getString(0))).map { ownedFile(it.path) }
                        candidates.addAll(files)
                        putPurge(db, id, files.mapNotNull { attachmentHash(db, it) }.toSet())
                    }
                }
                db.delete("entries", "id=? AND deletedAt IS NOT NULL", args)
            }
        }
        removeUnreferenced(candidates, keepDraft)
        return removed
    }

    private fun removeUnreferenced(candidates: Set<File>, keepDraft: List<Attachment> = emptyList()) {
        if (candidates.isNotEmpty()) {
            val db = helper.writableDatabase
            val referenced = referencedOriginals(keepDraft)
            candidates.filterNot { it in referenced }.forEach { file ->
                if (file.delete()) db.delete("sync_hashes", "path=?", arrayOf(file.path))
            }
        }
    }

    private fun putPurge(db: SQLiteDatabase, id: String, hashes: Set<String>) {
        db.replaceOrThrow("purged_entries", null, ContentValues().apply {
            put("id", id)
            put("hashes", JSONArray(hashes.sorted()).toString())
        })
    }

    private fun attachmentHash(db: SQLiteDatabase, file: File): String? {
        val exists = file.isFile
        val args = if (exists) arrayOf(file.path, file.length().toString(), file.lastModified().toString()) else arrayOf(file.path)
        val match = if (exists) "path=? AND size=? AND modified=?" else "path=?"
        db.rawQuery("SELECT hash FROM sync_hashes WHERE $match", args).use { if (it.moveToFirst()) return it.getString(0) }
        if (!exists) return null
        return fileHash(file).also { hash ->
            db.replaceOrThrow("sync_hashes", null, ContentValues().apply {
                put("path", file.path)
                put("size", file.length())
                put("modified", file.lastModified())
                put("hash", hash)
            })
        }
    }

    suspend fun addTag(name: String): Tag = mutate {
        val normalized = cleanTag(name)
        val db = helper.writableDatabase
        db.transaction {
            allTags(db).firstOrNull { it.name.equals(normalized, true) } ?: Tag(name = normalized).also { putTag(db, it) }
        }
    }

    suspend fun reorderTags(ids: List<String>) = mutate {
        val db = helper.writableDatabase
        db.transaction {
            require(ids.size == ids.distinct().size && ids.toSet() == allTags(db).map { it.id }.toSet()) { "标签已变化，请重试" }
            ids.forEachIndexed { index, id -> db.update("tags", ContentValues().apply { put("position", index) }, "id=?", arrayOf(id)) }
        }
    }

    suspend fun renameTag(id: String, name: String) = mutate {
        val normalized = cleanTag(name)
        val db = helper.writableDatabase
        db.transaction {
            val tags = allTags(db)
            require(tags.any { it.id == id }) { "标签已被删除" }
            require(tags.none { it.id != id && it.name.equals(normalized, true) }) { "已存在同名标签" }
            db.update("tags", ContentValues().apply { put("name", normalized) }, "id=?", arrayOf(id))
            Unit
        }
    }

    suspend fun deleteTag(id: String) = mutate {
        val db = helper.writableDatabase
        db.transaction { db.delete("tags", "id=?", arrayOf(id)); Unit }
        PreferencesStore(app).removeTagFilter(id)
    }

    /** Always copies the entire original, including text files. Only a complete file becomes visible. */
    suspend fun importAttachment(uri: Uri): Attachment = io {
        var displayName: String? = null
        var declaredSize: Long? = null
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        require((declaredSize ?: 0) <= MAX_FILE_BYTES) { "单个文件不能超过 100 MiB" }
        val name = (displayName ?: uri.lastPathSegment ?: "未命名文件")
            .substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), "").take(512).ifBlank { "未命名文件" }
        val mime = app.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val file = File(originals, "${newId()}.bin")
        val partial = File(originals, "${file.name}.partial")
        try {
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().buffered().use { output -> copyLimited(input, output, MAX_FILE_BYTES) }
            } ?: throw IOException("无法读取所选文件，请重新选择")
            if (!partial.renameTo(file)) throw IOException("文件保存失败，请重试")
            Attachment(name = name, mime = mime, size = bytes, path = file.canonicalPath)
        } finally {
            partial.delete()
        }
    }

    /** Caller passes active draft attachments; only files owned by this app can be collected. */
    suspend fun cleanupUnused(keepDraft: List<Attachment> = emptyList()) = io {
        val used = referencedOriginals(keepDraft).mapTo(mutableSetOf()) { it.path }
        originals.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.canonicalPath !in used && !file.delete()) throw IOException("部分未使用的附件无法清理，请重试")
        }
    }

    private fun referencedOriginals(keepDraft: List<Attachment>): Set<File> = buildSet {
        keepDraft.forEach { add(ownedFile(it.path)) }
        helper.readableDatabase.rawQuery("SELECT attachments FROM entries", null).use { cursor ->
            while (cursor.moveToNext()) readAttachments(JSONArray(cursor.getString(0))).forEach { add(ownedFile(it.path)) }
        }
    }

    /** Portable ZIP contains a schema manifest plus raw originals; settings and API keys are excluded. */
    suspend fun exportBackup(uri: Uri) = io {
        val library = loadLocked()
        require(library.entries.size <= MAX_ENTRIES && library.tags.size <= MAX_TAGS) { "条目或标签数量超过备份上限" }
        val files = linkedMapOf<String, Pair<String, File>>()
        val entriesJson = JSONArray()
        library.entries.forEach { entry ->
            val attachments = JSONArray()
            entry.attachments.forEach { attachment ->
                val file = verifyAttachment(attachment)
                val fileInfo = files.getOrPut(file.canonicalPath) { "files/${newId()}.bin" to file }
                attachments.put(attachmentJson(attachment).apply { remove("path"); put("file", fileInfo.first) })
            }
            entriesJson.put(entryJson(entry).apply { put("attachments", attachments) })
        }
        val fileManifest = JSONArray()
        require(files.size <= MAX_ARCHIVE_FILES) { "原始文件数量超过备份上限" }
        files.values.forEach { (name, file) ->
            fileManifest.put(JSONObject().put("file", name).put("size", file.length()).put("sha256", sha256(file)))
        }
        val manifest = JSONObject().put("schemaVersion", BACKUP_VERSION)
            .put("tags", JSONArray().apply { library.tags.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
            .put("entries", entriesJson).put("files", fileManifest).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "备份目录过大，请减少条目后重试" }
        require(files.values.sumOf { it.second.length() } + manifest.size <= MAX_BACKUP_BYTES) { "备份超过 2 GiB 上限" }
        val stream = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入备份位置")
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
            files.values.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name)); file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    /** Validate everything before committing. New IDs ensure restore never overwrites local entries. */
    suspend fun restoreBackup(uri: Uri) = mutate {
        val staging = File(app.cacheDir, "restore-${newId()}")
        if (!staging.mkdirs()) throw IOException("无法创建备份暂存目录")
        val installedFiles = mutableListOf<File>()
        var committed = false
        try {
            val archiveFiles = unpackBackup(uri, staging)
            val manifestFile = archiveFiles["manifest.json"] ?: throw IOException("备份缺少 manifest.json")
            val manifest = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }
                .getOrElse { throw IOException("备份目录损坏", it) }
            require(manifest.getInt("schemaVersion") == BACKUP_VERSION) { "此备份版本暂不支持" }
            val filesJson = manifest.getJSONArray("files")
            val declaredFiles = linkedMapOf<String, File>()
            require(filesJson.length() <= MAX_ARCHIVE_FILES) { "备份文件数量过多" }
            repeat(filesJson.length()) { index ->
                val item = filesJson.getJSONObject(index)
                val name = item.getString("file")
                require(FILE_NAME.matches(name) && name !in declaredFiles) { "备份文件路径无效或重复" }
                val file = archiveFiles[name] ?: throw IOException("备份缺少原始文件")
                require(file.length() == item.getLong("size") && sha256(file) == item.getString("sha256")) { "备份文件校验失败" }
                declaredFiles[name] = file
            }
            require(archiveFiles.keys == declaredFiles.keys + "manifest.json") { "备份包含未声明的文件" }
            val importedTags = readTags(manifest.getJSONArray("tags"))
            val entriesJson = manifest.getJSONArray("entries")
            require(entriesJson.length() <= MAX_ENTRIES) { "备份条目数量过多" }
            val importedEntries = (0 until entriesJson.length()).map { readBackupEntry(entriesJson.getJSONObject(it), declaredFiles) }
            require(importedEntries.map { it.id }.distinct().size == importedEntries.size) { "备份条目标识重复" }
            val importedTagIds = importedTags.map { it.id }.toSet()
            require(importedEntries.all { importedTagIds.containsAll(it.tagIds) }) { "备份含有不存在的标签" }
            require(importedEntries.flatMap { it.attachments }.map { it.path }.toSet() == declaredFiles.keys) { "备份含有未引用文件" }

            val pathMap = declaredFiles.mapValues { (_, staged) ->
                val final = File(originals, "${newId()}.bin")
                // Cache and files are usually the same volume; use bounded copying if rename fails.
                if (!staged.renameTo(final)) staged.inputStream().use { input ->
                    try { final.outputStream().use { output -> copyLimited(input, output, MAX_FILE_BYTES) } }
                    catch (error: Throwable) { final.delete(); throw error }
                }
                installedFiles += final
                final.canonicalPath
            }
            val db = helper.writableDatabase
            db.transaction {
                val currentTags = allTags(db).toMutableList()
                val tagMap = importedTags.associate { source ->
                    val tag = currentTags.firstOrNull { it.name.equals(source.name, true) }
                        ?: Tag(name = source.name).also { putTag(db, it); currentTags += it }
                    source.id to tag.id
                }
                importedEntries.forEach { entry ->
                    putEntry(db, entry.copy(id = newId(), tagIds = entry.tagIds.map { tagMap.getValue(it) }.toSet(),
                        attachments = entry.attachments.map { it.copy(id = newId(), path = pathMap.getValue(it.path)) }))
                }
            }
            committed = true
        } finally {
            if (!committed) installedFiles.forEach { it.delete() }
            staging.deleteRecursively()
        }
    }

    override suspend fun captureSync(): SyncSnapshot = io { captureSyncLocked() }

    private fun captureSyncLocked(): SyncSnapshot {
        val db = helper.writableDatabase
        val previous = db.rawQuery("SELECT value FROM sync_meta WHERE key LIKE 'document.%' ORDER BY key", null).use { cursor ->
            val bytes = java.io.ByteArrayOutputStream()
            while (cursor.moveToNext()) {
                val chunk = cursor.getBlob(0)
                require(bytes.size() + chunk.size <= MAX_SYNC_BYTES) { "本机同步目录过大" }
                bytes.write(chunk)
            }
            if (bytes.size() == 0) SyncDocument() else SyncDocument.decode(bytes.toString(Charsets.UTF_8.name()))
        }
        val files = linkedMapOf<String, File>()
        val payloads = linkedMapOf<String, Pair<String, String>>()
        val library = loadLocked()
        library.tags.forEachIndexed { index, tag -> payloads["tag:${tag.id}"] = tag.id to canonical(
            JSONObject().put("id", tag.id).put("name", tag.name).put("position", index)) }
        library.entries.forEach { entry ->
            val attachments = JSONArray()
            entry.attachments.forEach { attachment ->
                val file = verifyAttachment(attachment)
                val hash = checkNotNull(attachmentHash(db, file)) { "无法读取附件" }
                files[hash] = file
                attachments.put(attachmentJson(attachment).apply { remove("path"); put("hash", hash) })
            }
            payloads["entry:${entry.id}"] = entry.id to canonical(entryJson(entry)
                .put("tagIds", JSONArray(entry.tagIds.sorted())).put("attachments", attachments))
        }
        val records = previous.records.toMutableMap()
        (records.keys + payloads.keys).toSet().forEach { key ->
            val old = records[key]; val payload = payloads[key]?.second
            if (old == null || old.payload != payload) {
                val clock = old?.clock.orEmpty().toMutableMap()
                clock[syncActor] = Math.addExact(clock[syncActor] ?: 0, 1)
                records[key] = SyncRecord(key.substringBefore(':'), old?.id ?: payloads.getValue(key).first, clock, payload)
            }
        }
        val purged = db.rawQuery("SELECT id, hashes FROM purged_entries", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), strings(JSONArray(cursor.getString(1))).toSet()) }
        }
        val retained = db.rawQuery("SELECT id, attachments FROM entries WHERE deletedAt IS NOT NULL", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val hashes = readAttachments(JSONArray(cursor.getString(1))).mapNotNull {
                        val file = ownedFile(it.path)
                        attachmentHash(db, file)?.also { hash -> if (file.isFile) files.putIfAbsent(hash, file) }
                    }
                    put(cursor.getString(0), hashes.toSet())
                }
            }
        }
        val doc = SyncDocument(records, purged, retained).withoutPurged(purged)
        if (doc != previous) saveSyncDocument(doc)
        return SyncSnapshot(doc, files)
    }

    /** Re-read local changes under the same lock used by all foreground writes before committing remote changes. */
    override suspend fun applySync(remote: SyncDocument, downloaded: Map<String, File>): SyncSnapshot = io {
        val current = captureSyncLocked()
        val merged = SyncMerge.merge(current.document, remote)
        if (merged == current.document) return@io current
        val db = helper.writableDatabase
        val installed = mutableListOf<File>()
        val purgedFiles = mutableSetOf<File>()
        val paths = current.files.toMutableMap()
        try {
            merged.blobs().forEach { (hash, size) ->
                if (paths[hash]?.isFile != true) {
                    val staged = downloaded[hash] ?: throw IOException("同步原件尚未下载完成，请重试")
                    require(staged.length() == size && fileHash(staged) == hash) { "同步原件校验失败" }
                    val final = File(originals, "${newId()}.bin")
                    installed += final
                    staged.inputStream().use { input -> final.outputStream().use { copyLimited(input, it, MAX_FILE_BYTES) } }
                    paths[hash] = final
                }
            }
            val liveTags = merged.records.values.filter { it.kind == "tag" && it.payload != null }
                .sortedWith(compareBy({ JSONObject(it.payload!!).getInt("position") }, { it.id }))
            val tagIds = liveTags.map { it.id }.toSet()
            db.transaction {
                merged.purged.forEach { (id, hashes) ->
                    if (current.document.purged[id] != hashes) putPurge(db, id, hashes)
                    db.rawQuery("SELECT attachments FROM entries WHERE id=?", arrayOf(id)).use { cursor ->
                        if (cursor.moveToFirst()) readAttachments(JSONArray(cursor.getString(0))).forEach { purgedFiles += ownedFile(it.path) }
                    }
                    db.delete("entries", "id=?", arrayOf(id))
                }
                val deletedAt = ContentValues().apply { put("deletedAt", System.currentTimeMillis()) }
                merged.records.values.filter { it.kind == "entry" && it.payload == null }.forEach { record ->
                    db.update("entries", deletedAt, "id=? AND deletedAt IS NULL", arrayOf(record.id))
                }
                val trashTags = db.rawQuery("SELECT entryId, tagId FROM entry_tags JOIN entries ON entryId=entries.id WHERE deletedAt IS NOT NULL", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }
                db.delete("entries", "deletedAt IS NULL", null)
                db.delete("tags", null, null)
                liveTags.forEachIndexed { index, r ->
                    db.insertOrThrow("tags", null, ContentValues().apply {
                        put("id", r.id); put("name", JSONObject(r.payload!!).getString("name")); put("position", index)
                    })
                }
                trashTags.filter { (_, tagId) -> tagId in tagIds }.forEach { (entryId, tagId) ->
                    db.insertOrThrow("entry_tags", null, ContentValues().apply { put("entryId", entryId); put("tagId", tagId) })
                }
                merged.records.values.filter { it.kind == "entry" && it.payload != null }.forEach { r ->
                    val data = JSONObject(r.payload!!)
                    val attachments = data.getJSONArray("attachments")
                    for (i in 0 until attachments.length()) {
                        val f = attachments.getJSONObject(i); f.put("path", paths.getValue(f.getString("hash")).canonicalPath)
                    }
                    putEntry(db, Entry(id = r.id, title = data.getString("title"), summary = data.getString("summary"),
                        body = data.getString("body"), bodyTitle = data.getString("bodyTitle"),
                        createdAt = data.getLong("createdAt"), updatedAt = data.getLong("updatedAt"),
                        parseWarnings = strings(data.getJSONArray("parseWarnings")), attachments = readAttachments(attachments),
                        tagIds = strings(data.getJSONArray("tagIds")).filter { it in tagIds }.toSet(), iconKey = data.optString("iconKey", "auto")))
                }
                saveSyncDocument(merged)
            }
        } catch (e: Throwable) { installed.forEach { it.delete() }; throw e }
        removeUnreferenced(purgedFiles)
        // Canonicalize local tag order and references once, recording those changes causally.
        captureSyncLocked().also { SyncSignals.remoteChanged() }
    }

    private fun saveSyncDocument(document: SyncDocument) {
        val text = document.encode()
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_SYNC_BYTES) { "同步目录超过 32 MiB" }
        SyncDocument.decode(text)
        val db = helper.writableDatabase
        fun write() {
            db.delete("sync_meta", "key LIKE ?", arrayOf("document.%"))
            // Keep each row well below Android's CursorWindow limit. Split UTF-8 bytes, not characters.
            val chunkSize = 128 * 1024
            for ((index, start) in (bytes.indices step chunkSize).withIndex()) {
                db.insertOrThrow("sync_meta", null, ContentValues().apply {
                    put("key", "document." + index.toString().padStart(5, '0'))
                    put("value", bytes.copyOfRange(start, minOf(bytes.size, start + chunkSize)))
                })
            }
        }
        if (db.inTransaction()) write() else db.transaction { write() }
    }

    private fun loadLocked(): Library {
        val db = helper.readableDatabase
        val entries = db.rawQuery("SELECT * FROM entries WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val id = cursor.string("id")
                val tags = db.rawQuery("SELECT tagId FROM entry_tags WHERE entryId=?", arrayOf(id)).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(0)) }
                }
                add(Entry(id = id, title = cursor.string("title"), summary = cursor.string("summary"), body = cursor.string("body"),
                    attachments = readAttachments(JSONArray(cursor.string("attachments"))), tagIds = tags,
                    createdAt = cursor.long("createdAt"), updatedAt = cursor.long("updatedAt"),
                    parseWarnings = strings(JSONArray(cursor.string("warnings"))), iconKey = cursor.string("iconKey"), bodyTitle = cursor.string("bodyTitle")))
            } }
        }
        return Library(entries, allTags(db))
    }

    private fun putEntry(db: SQLiteDatabase, entry: Entry) {
        db.insertWithOnConflict("entries", null, ContentValues().apply {
            put("bodyTitle", entry.bodyTitle); put("id", entry.id); put("iconKey", entry.iconKey.take(64)); put("title", entry.title); put("summary", entry.summary); put("body", entry.body)
            put("attachments", JSONArray().apply { entry.attachments.forEach { put(attachmentJson(it)) } }.toString())
            put("warnings", JSONArray(entry.parseWarnings).toString()); put("createdAt", entry.createdAt); put("updatedAt", entry.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "条目保存失败" } }
        db.delete("entry_tags", "entryId=?", arrayOf(entry.id))
        entry.tagIds.forEach { tagId -> db.insertOrThrow("entry_tags", null, ContentValues().apply { put("entryId", entry.id); put("tagId", tagId) }) }
    }

    private fun putTag(db: SQLiteDatabase, tag: Tag) {
        db.insertOrThrow("tags", null, ContentValues().apply { put("id", tag.id); put("name", tag.name); put("position", db.rawQuery("SELECT COALESCE(MAX(position), -1)+1 FROM tags", null).use { it.moveToFirst(); it.getInt(0) }) })
    }

    private fun allTags(db: SQLiteDatabase): List<Tag> = db.rawQuery("SELECT id,name FROM tags ORDER BY position,name COLLATE NOCASE,id", null).use {
        buildList { while (it.moveToNext()) add(Tag(it.getString(0), it.getString(1))) }
    }

    private fun cleanTag(name: String): String = name.trim().removePrefix("#").trim().also {
        require(it.isNotBlank() && it.length <= 60 && it.none(Char::isISOControl)) { "标签需要 1–60 个有效字符" }
    }

    private fun ownedFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == originals.canonicalFile && OWNED_NAME.matches(file.name)) { "附件文件路径无效" }
        return file
    }

    private fun verifyAttachment(attachment: Attachment): File = ownedFile(attachment.path).also {
        require(it.isFile && it.length() == attachment.size && attachment.size in 0..MAX_FILE_BYTES) { "附件已丢失或损坏，请重新添加：${attachment.name}" }
    }

    private fun attachmentJson(attachment: Attachment): JSONObject = JSONObject().put("id", attachment.id).put("name", attachment.name)
        .put("title", attachment.title).put("mime", attachment.mime).put("size", attachment.size).put("path", attachment.path)

    private fun entryJson(entry: Entry): JSONObject = JSONObject().put("bodyTitle", entry.bodyTitle).put("iconKey", entry.iconKey).put("id", entry.id).put("title", entry.title).put("summary", entry.summary)
        .put("body", entry.body).put("tagIds", JSONArray(entry.tagIds.toList())).put("createdAt", entry.createdAt)
        .put("updatedAt", entry.updatedAt).put("parseWarnings", JSONArray(entry.parseWarnings))

    private fun readAttachments(array: JSONArray): List<Attachment> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        Attachment(id = item.getString("id"), name = item.getString("name"), mime = item.getString("mime"), size = item.getLong("size"), path = item.getString("path"), title = item.optString("title", ""))
    }

    private fun readTags(array: JSONArray): List<Tag> {
        require(array.length() <= MAX_TAGS) { "备份标签数量过多" }
        val tags = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Tag(id = item.getString("id").also { require(it.isNotBlank()) }, name = cleanTag(item.getString("name")))
        }
        require(tags.map { it.id }.distinct().size == tags.size && tags.map { it.name.lowercase(Locale.ROOT) }.distinct().size == tags.size) { "备份标签重复" }
        return tags
    }

    private fun readBackupEntry(item: JSONObject, files: Map<String, File>): Entry {
        val sourceAttachments = item.getJSONArray("attachments")
        require(sourceAttachments.length() <= MAX_ARCHIVE_FILES) { "单条附件过多" }
        val attachments = (0 until sourceAttachments.length()).map { index ->
            val source = sourceAttachments.getJSONObject(index)
            val fileName = source.getString("file")
            val file = files[fileName] ?: throw IOException("附件未在备份中声明")
            val size = source.getLong("size")
            require(size == file.length()) { "附件长度校验失败" }
            Attachment(id = source.getString("id"), name = source.getString("name").take(512), mime = source.getString("mime").take(256), size = size, path = fileName, title = source.optString("title", ""))
        }
        require(attachments.map { it.id }.distinct().size == attachments.size) { "备份附件标识重复" }
        return Entry(id = item.getString("id"), title = item.getString("title"), summary = item.getString("summary"), body = item.getString("body"),
            attachments = attachments, tagIds = strings(item.getJSONArray("tagIds")).toSet(), createdAt = item.getLong("createdAt"),
            updatedAt = item.getLong("updatedAt"), parseWarnings = strings(item.getJSONArray("parseWarnings")), iconKey = item.optString("iconKey", "auto").take(64), bodyTitle = item.optString("bodyTitle", ""))
    }

    private fun unpackBackup(uri: Uri, staging: File): Map<String, File> {
        val files = linkedMapOf<String, File>()
        var totalBytes = 0L
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取备份")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.size <= MAX_ARCHIVE_FILES) { "备份文件数量过多" }
                val name = entry.name
                require(!entry.isDirectory && (name == "manifest.json" || FILE_NAME.matches(name)) && name !in files) { "备份路径无效或重复" }
                val limit = if (name == "manifest.json") MAX_MANIFEST_BYTES.toLong() else MAX_FILE_BYTES
                require(entry.size <= limit) { "备份中有过大的文件" }
                // Use generated staging names rather than any untrusted archive path.
                val destination = File(staging, newId())
                val bytes = destination.outputStream().buffered().use { output -> copyLimited(zip, output, minOf(limit, MAX_BACKUP_BYTES - totalBytes)) }
                totalBytes += bytes
                files[name] = destination
                zip.closeEntry()
            }
        }
        return files
    }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("文件超过允许的大小")
            output.write(buffer, 0, count)
        }
        return total
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    private suspend fun <T> mutate(block: () -> T): T = io { block().also { SyncSignals.localChanged() } }

    private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
        beginTransaction()
        try { return block().also { setTransactionSuccessful() } } finally { endTransaction() }
    }

    private companion object {
        const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        val DATABASE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAX_ARCHIVE_FILES = 20_000
        const val MAX_ENTRIES = 10_000
        const val MAX_TAGS = 5_000
        const val BACKUP_VERSION = 1
        val OWNED_NAME = Regex("[0-9a-fA-F-]{36}\\.bin")
        val FILE_NAME = Regex("files/[0-9a-fA-F-]{36}\\.bin")
    }
}

data class TrashedEntry(val id: String, val title: String, val deletedAt: Long)

private class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "library.db", null, 6) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY,title TEXT NOT NULL,summary TEXT NOT NULL,body TEXT NOT NULL,attachments TEXT NOT NULL,warnings TEXT NOT NULL,iconKey TEXT NOT NULL DEFAULT 'auto',bodyTitle TEXT NOT NULL DEFAULT '',createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tags(id TEXT PRIMARY KEY,name TEXT NOT NULL COLLATE NOCASE UNIQUE,position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE entry_tags(entryId TEXT NOT NULL REFERENCES entries(id) ON DELETE CASCADE,tagId TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,PRIMARY KEY(entryId,tagId))")
        db.execSQL("CREATE INDEX entry_updated ON entries(updatedAt)")
        db.execSQL("CREATE INDEX entry_created ON entries(createdAt)")
        db.execSQL("CREATE INDEX tag_entries ON entry_tags(tagId)")
        createSyncTables(db)
        addTrashColumn(db)
        createPurgeTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'auto'")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN bodyTitle TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tags ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            val ids = db.rawQuery("SELECT id FROM tags ORDER BY name COLLATE NOCASE,id", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            ids.forEachIndexed { index, id -> db.execSQL("UPDATE tags SET position=? WHERE id=?", arrayOf<Any>(index, id)) }
        }
        if (oldVersion < 4) createSyncTables(db)
        if (oldVersion < 5) addTrashColumn(db)
        if (oldVersion < 6) createPurgeTable(db)
    }
    private fun createPurgeTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE purged_entries(id TEXT PRIMARY KEY, hashes TEXT NOT NULL)")
    }
    private fun addTrashColumn(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN deletedAt INTEGER")
        db.execSQL("CREATE INDEX entry_deleted ON entries(deletedAt)")
    }
    private fun createSyncTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sync_meta(key TEXT PRIMARY KEY,value BLOB NOT NULL)")
        db.execSQL("CREATE TABLE sync_hashes(path TEXT PRIMARY KEY,size INTEGER NOT NULL,modified INTEGER NOT NULL,hash TEXT NOT NULL)")
    }
}

