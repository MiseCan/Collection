package com.collection.sync

import android.content.Context
import android.util.AtomicFile
import com.collection.data.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SyncSnapshot(val document: SyncDocument, val files: Map<String, File>)
interface SyncLocalStore {
    suspend fun captureSync(): SyncSnapshot
    suspend fun applySync(remote: SyncDocument, downloaded: Map<String, File>): SyncSnapshot
}

internal data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: Int, val cleanupPending: Boolean)

internal class SyncEngine(
    private val local: SyncLocalStore,
    private val client: WebDavClient,
    private val actor: String,
    private val cache: File,
    private val uploaded: () -> Set<String> = { emptySet() },
    private val rememberUpload: (String) -> Unit = {},
    private val ensureCurrent: () -> Unit = {},
) {
    suspend fun run(progress: (String) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        require(cache.isDirectory || cache.mkdirs()) { "无法创建同步暂存目录" }
        val staging = File(cache, "transfer-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            ensureCurrent()
            client.initialize()
            client.withWriteLock(actor) { synchronizeLocked(staging, progress) }
        } finally { staging.deleteRecursively() }
    }

    private suspend fun synchronizeLocked(staging: File, progress: (String) -> Unit): SyncResult {
        ensureCurrent()
        progress("正在读取云端目录…")
        val heads = client.heads()
        val documents = heads.map { head ->
            val cached = File(cache, "${head.actor}.cache")
            val saved = if (cached.isFile && cached.length() <= MAX_SYNC_BYTES + 4096) runCatching { JSONObject(cached.readText()) }.getOrNull() else null
            val document = if (head.etag != null && saved?.optString("etag") == head.etag)
                runCatching { SyncDocument.decode(saved.getString("document")) }.getOrNull() else null
            document ?: client.readHead(head.actor).also { doc ->
                val atomic = AtomicFile(cached); val stream = atomic.startWrite()
                try { stream.write(JSONObject().put("etag", head.etag).put("document", doc.encode()).toString().toByteArray()); atomic.finishWrite(stream) }
                catch (e: Throwable) { atomic.failWrite(stream); throw e }
            }
        }
        ensureCurrent()
        val initial = local.captureSync()
        val merged = SyncMerge.merge(initial.document, *documents.toTypedArray())
        val received = linkedMapOf<String, File>()
        val missing = merged.blobs().filterKeys { initial.files[it]?.isFile != true }
        missing.entries.forEachIndexed { i, (hash, size) ->
            ensureCurrent(); progress("正在下载原件 ${i + 1}/${missing.size}…")
            val file = File(staging, hash)
            client.downloadBlob(hash, size, file); received[hash] = file
        }
        ensureCurrent()
        progress("正在合并条目…")
        val applied = local.applySync(merged, received)
        val cloudBlobs = documents.flatMap { it.blobs().keys }.toSet() + uploaded()
        val purgeHashes = applied.document.purged.values.flatten().toSet()
        val outgoing = applied.document.blobs().filterKeys { hash ->
            if (hash in purgeHashes) !client.blobExists(hash) else hash !in cloudBlobs
        }
        outgoing.keys.forEachIndexed { i, hash ->
            ensureCurrent(); progress("正在上传原件 ${i + 1}/${outgoing.size}…")
            val file = applied.files[hash] ?: error("待上传原件已变化，请重试")
            require(file.isFile && fileHash(file) == hash) { "原件已变化，请重新同步" }
            client.uploadBlob(hash, file); rememberUpload(hash)
        }
        ensureCurrent()
        val previousOwn = heads.indexOfFirst { it.actor == actor }.takeIf { it >= 0 }?.let { documents[it] }
        if (previousOwn?.encode() != applied.document.encode()) {
            progress("正在提交同步目录…")
            client.publish(actor, applied.document)
        }
        val cleanupPending = clearCloudTrash(heads, documents, applied.document, progress)
        val before = (initial.document.records.keys + documents.flatMap { it.records.keys }).toSet()
        val conflicts = applied.document.records.values.count {
            it.key !in before && it.kind == "entry" && it.payload?.let { p -> JSONObject(p).getString("title").endsWith("（冲突副本）") } == true
        }
        return SyncResult(outgoing.size, received.size, conflicts, cleanupPending)
    }

    private suspend fun clearCloudTrash(heads: List<RemoteHead>, documents: List<SyncDocument>, own: SyncDocument,
        progress: (String) -> Unit): Boolean {
        if (own.purged.isEmpty()) return false
        val remaining = mutableListOf(own)
        for ((head, document) in heads.zip(documents)) {
            if (head.actor == actor) continue
            val containsPurged = document.records.values.any { it.kind == "entry" && it.id in own.purged } ||
                document.retained.orEmpty().keys.any { it in own.purged }
            if (containsPurged) {
                ensureCurrent()
                progress("正在清除云端回收站记录…")
                val cleaned = document.withoutPurged(own.purged)
                client.publish(head.actor, cleaned)
                remaining += cleaned
            } else remaining += document
        }
        val referenced = remaining.flatMap { it.blobs().keys + it.retained.orEmpty().values.flatten() }.toSet()
        val candidates = mutableMapOf<String, MutableList<String>>()
        own.purged.forEach { (id, hashes) ->
            hashes.filterNot { it in referenced }.forEach { hash -> candidates.getOrPut(hash, ::mutableListOf).add(id) }
        }
        // Older heads did not publish their trash attachments. Wait for those devices before reclaiming shared bytes.
        if (remaining.any { it.retained == null }) return candidates.isNotEmpty()
        for ((hash, ids) in candidates) {
            ensureCurrent()
            val receipt = File(cache, "purged-$hash")
            val generation = digest(ids.sorted().joinToString("\n").toByteArray())
            if (receipt.isFile && receipt.readText() == generation) continue
            progress("正在清除云端原件…")
            if (client.blobExists(hash)) client.deleteBlob(hash)
            receipt.writeText(generation)
        }
        return false
    }
}

internal object SyncCoordinator {
    private val lock = Mutex()
    suspend fun synchronize(context: Context): SyncResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext; val settings = SyncSettings(app)
            val config = settings.read().validated(); val actor = SyncIdentity.actor(app)
            val last = settings.status().lastSuccess
            fun status(message: String, running: Boolean = true) { settings.status(SyncStatus(running, message, last)) }
            status("正在连接云盘…")
            try {
                val client = WebDavClient(config, reserve = { settings.reserveRequest(config.identity) })
                val result = SyncEngine(LibraryStore(app), client, actor, File(app.noBackupFilesDir, "sync-cache/${config.identity}"),
                    uploaded = { settings.uploaded(config.identity) }, rememberUpload = { settings.markUploaded(config.identity, it) },
                    ensureCurrent = {
                        val current = settings.read()
                        require(current.identity == config.identity && current.password == config.password) { "同步配置已变化，本次同步已停止" }
                    }).run { status(it) }
                val message = buildString {
                    append("同步完成 · 上传 ${result.uploaded}，下载 ${result.downloaded}")
                    if (result.conflicts > 0) append("，保留 ${result.conflicts} 个冲突副本")
                    if (result.cleanupPending) append("。等待旧设备更新并同步后清理云端原件")
                }
                settings.status(SyncStatus(false, message, System.currentTimeMillis()))
                result
            } catch (e: CancellationException) { status("同步已暂停，下次继续", false); throw e }
            catch (e: Exception) {
                // Exceptions from the HTTP layer omit URLs, credentials, and response bodies.
                val message = if (e is IllegalArgumentException || e is SyncFailure) e.message ?: "同步失败，请重试" else "同步未完成，请检查网络和云端目录后重试"
                status(message, false); throw e
            }
        }
    }
    suspend fun test(context: Context, config: SyncConfig) = lock.withLock {
        withContext(Dispatchers.IO) {
            val settings = SyncSettings(context)
            WebDavClient(config, reserve = { settings.reserveRequest(config.identity) }).test()
        }
    }
}
