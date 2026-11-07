package com.collection.data

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.collection.domain.Entry
import com.collection.domain.HomePreferences
import com.collection.domain.newId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Executes the real SQLite, original-file and ZIP paths without relying on an emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreRuntimeTest {
    private lateinit var app: Context
    private lateinit var context: IsolatedContext
    private lateinit var root: File
    private lateinit var store: LibraryStore
    private lateinit var provider: StreamProvider
    private lateinit var authority: String

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.filesDir, "originals/runtime-test-${newId()}").apply { check(mkdirs()) }
        context = IsolatedContext(app, root)
        store = LibraryStore(context)
        authority = "com.collection.test.${newId()}"
        provider = StreamProvider()
        provider.attachInfo(app, ProviderInfo().apply { this.authority = this@StoreRuntimeTest.authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @After fun tearDown() {
        context.closeDatabases()
        check(root.canonicalFile.parentFile == File(app.filesDir, "originals").canonicalFile)
        // Windows may keep Robolectric native SQLite handles until sandbox teardown.
        if (!root.deleteRecursively()) root.walkTopDown().forEach { it.deleteOnExit() }
        app.deleteSharedPreferences(context.prefsName)
    }

    @Test fun createUpdateAndReopenRetainSummaryAndBodyThenDelete() = runBlocking {
        val original = Entry(title = "项目记录", summary = "第一版总结", body = "原始记录第一行\n第二行")
        store.saveEntry(original)
        store.saveEntry(original.copy(summary = "更新后的总结"))
        val reopened = LibraryStore(context)
        val stored = reopened.load().entries.single()
        assertEquals(original.id, stored.id)
        assertEquals("更新后的总结", stored.summary)
        assertEquals(original.body, stored.body)
        assertEquals(original.createdAt, stored.createdAt)
        store.deleteEntry(original.id)
        assertTrue(reopened.load().entries.isEmpty())
    }

    @Test fun syncMetadataSurvivesReopenAndRecordsEditsAndDeletesWithoutChangingIds() = runBlocking {
        val entry = Entry(title = "同步条目", body = "初始原文")
        store.saveEntry(entry)
        val first = store.captureSync().document
        val key = "entry:${entry.id}"
        val reopened = LibraryStore(context)
        assertEquals(first.encode(), reopened.captureSync().document.encode())
        store.saveEntry(store.load().entries.single().copy(body = "本地新内容"))
        val second = reopened.captureSync().document
        assertTrue(second.records.getValue(key).dominates(first.records.getValue(key)))
        assertNotEquals(first.records[key], second.records[key])
        store.deleteEntry(entry.id)
        val deleted = reopened.captureSync().document.records.getValue(key)
        assertNull(deleted.payload)
        assertTrue(deleted.dominates(second.records.getValue(key)))
    }

    @Test fun trashRestoresOriginalsAndPurgingOneEntryKeepsFilesSharedByOthers() = runBlocking {
        val source = File(root, "source.txt").apply { writeText("要恢复的原件") }
        val attachment = store.importAttachment(uriFor(source))
        val original = File(attachment.path)
        val tag = store.addTag("资料")
        val first = Entry(title = "恢复", body = "原文", attachments = listOf(attachment), tagIds = setOf(tag.id))
        val second = Entry(title = "清除", attachments = listOf(attachment))
        store.saveEntry(first)
        store.saveEntry(second)
        store.deleteEntries(setOf(first.id, second.id))
        store.cleanupUnused()
        assertTrue(original.isFile)
        assertTrue(store.load().entries.isEmpty())
        assertEquals(setOf(first.id, second.id), LibraryStore(context).loadTrash().map { it.id }.toSet())
        assertEquals(1, store.restoreEntries(setOf(first.id)))
        assertEquals(1, store.purgeEntries(setOf(first.id, second.id)))
        val restored = store.load().entries.single()
        assertEquals(first.id, restored.id)
        assertEquals(first.tagIds, restored.tagIds)
        assertEquals(first.body, restored.body)
        assertEquals("要恢复的原件", original.readText())
        assertTrue(store.loadTrash().isEmpty())
        store.deleteEntry(first.id)
        assertEquals(1, store.purgeEntries(setOf(first.id)))
        assertFalse(original.exists())
    }

    @Test fun trashExpiresAfterThirtyDaysWithoutClearingNewerOrActiveEntries() = runBlocking {
        val older = Entry(title = "到期")
        val newer = Entry(title = "未到期")
        val active = Entry(title = "仍在使用")
        listOf(older, newer, active).forEach { store.saveEntry(it) }
        store.deleteEntries(setOf(older.id, newer.id))
        val retention = 30L * 24 * 60 * 60 * 1000
        val now = store.loadTrash().first().deletedAt
        context.openOrCreateDatabase("library.db", 0, null).use { db ->
            db.execSQL("UPDATE entries SET deletedAt=? WHERE id=?", arrayOf<Any>(now - retention, older.id))
        }
        assertEquals(0, store.purgeExpiredTrash(now - 1))
        assertEquals(1, store.purgeExpiredTrash(now))
        assertEquals(newer.id, store.loadTrash().single().id)
        assertEquals(active.id, store.load().entries.single().id)
        assertEquals(setOf(older.id), store.captureSync().document.purged.keys)
    }

    @Test fun multiMegabyteSyncMetadataRoundTripsAcrossCursorWindows() = runBlocking {
        val body = "中文😀".repeat(7_000)
        repeat(36) { store.saveEntry(Entry(title = "资料 $it", body = body)) }
        val initial = store.captureSync().document
        assertTrue(initial.encode().toByteArray().size > 2 * 1024 * 1024)
        assertEquals(initial.encode(), LibraryStore(context).captureSync().document.encode())
        assertTrue(store.load().entries.all { it.body == body })
    }

    @Test fun versionOneLibraryMigratesWithoutLosingExistingContentOrTags() = runBlocking {
        context.openOrCreateDatabase("library.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY,title TEXT NOT NULL,summary TEXT NOT NULL,body TEXT NOT NULL,attachments TEXT NOT NULL,warnings TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE tags(id TEXT PRIMARY KEY,name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE entry_tags(entryId TEXT NOT NULL,tagId TEXT NOT NULL)")
            db.execSQL("INSERT INTO entries VALUES('old','旧条目','保留的旧总结','原始文字','[]','[]',1,2)")
            db.execSQL("INSERT INTO tags VALUES('tag','已有标签')")
            db.execSQL("INSERT INTO entry_tags VALUES('old','tag')")
            db.version = 1
        }
        val migrated = store.load().entries.single()
        assertEquals("旧条目", migrated.title)
        assertEquals("原始文字", migrated.body)
        assertEquals(setOf("tag"), migrated.tagIds)
        assertEquals("auto", migrated.iconKey)
        store.saveEntry(migrated.copy(iconKey = "link"))
        assertEquals("link", LibraryStore(context).load().entries.single().iconKey)
    }

    @Test fun renameKeepsTagIdentityAndDeleteRemovesAssociationsAndPersistedFilter() = runBlocking {
        val tag = store.addTag("  工作 ")
        assertEquals(tag.id, store.addTag("工作").id)
        val other = store.addTag("家庭")
        store.saveEntry(Entry(title = "计划", tagIds = setOf(tag.id, other.id)))
        PreferencesStore(context).writeHome(HomePreferences(cards = false, tagIds = setOf(tag.id, other.id)))
        store.renameTag(tag.id, "项目")
        assertEquals("项目", store.load().tags.single { it.id == tag.id }.name)
        assertTrue(runCatching { store.renameTag(tag.id, "家庭") }.isFailure)
        store.deleteTag(tag.id)
        assertEquals(setOf(other.id), store.load().entries.single().tagIds)
        assertEquals(setOf(other.id), PreferencesStore(context).readHome().tagIds)
        assertFalse(PreferencesStore(context).readHome().cards)
    }

    @Test fun tagOrderPersistsAndDeletingTagKeepsEntriesAndNewTagsAppend() = runBlocking {
        val a = store.addTag("甲")
        val b = store.addTag("乙")
        val c = store.addTag("丙")
        store.saveEntry(Entry(title = "保留", tagIds = setOf(a.id, b.id)))
        store.reorderTags(listOf(c.id, a.id, b.id))
        assertEquals(listOf(c.id, a.id, b.id), LibraryStore(context).load().tags.map { it.id })
        store.deleteTag(a.id)
        val d = store.addTag("丁")
        val after = store.load()
        assertEquals(listOf(c.id, b.id, d.id), after.tags.map { it.id })
        assertEquals(setOf(b.id), after.entries.single().tagIds)
        assertTrue(runCatching { store.reorderTags(listOf(b.id, c.id)) }.isFailure)
    }

    @Test fun textOriginalAndBackupMergeRoundTripWithoutOverwritingExistingEntries() = runBlocking {
        val bytes = "保留原始资料\r\n第一行和第二行\n".toByteArray(Charsets.UTF_8)
        val source = File(root, "source.txt").apply { writeBytes(bytes) }
        val attachment = store.importAttachment(uriFor(source)).copy(title = "原始文档AI标题")
        assertEquals("source.txt", attachment.name)
        assertArrayEquals(bytes, File(attachment.path).readBytes())
        val tag = store.addTag("资料")
        val entry = Entry(title = "文档", summary = "中文总结", body = "手动备注", bodyTitle = "文字AI标题", iconKey = "email", attachments = listOf(attachment), tagIds = setOf(tag.id))
        store.saveEntry(entry)
        val backup = File(root, "backup.zip").apply { createNewFile() }
        store.exportBackup(uriFor(backup))
        store.restoreBackup(uriFor(backup))
        val merged = LibraryStore(context).load()
        assertEquals(2, merged.entries.size)
        assertEquals(1, merged.tags.size)
        assertTrue(merged.entries.any { it.id == entry.id })
        val restored = merged.entries.single { it.id != entry.id }
        assertEquals(entry.title, restored.title)
        assertEquals(entry.summary, restored.summary)
        assertEquals(entry.body, restored.body)
        assertEquals("email", restored.iconKey)
        assertEquals("文字AI标题", restored.bodyTitle)
        assertEquals("原始文档AI标题", restored.attachments.single().title)
        assertEquals(entry.tagIds, restored.tagIds)
        assertNotEquals(attachment.path, restored.attachments.single().path)
        assertArrayEquals(bytes, File(restored.attachments.single().path).readBytes())
    }

    @Test fun traversalBackupFailsBeforeMutationAndCleanupProtectsActiveDraft() = runBlocking {
        store.saveEntry(Entry(title = "保留的条目"))
        val source = File(root, "draft.txt").apply { writeText("尚未保存的草稿附件") }
        val draft = store.importAttachment(uriFor(source))
        val unsafe = File(root, "unsafe.zip")
        ZipOutputStream(unsafe.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt")); zip.write("escape".toByteArray()); zip.closeEntry()
        }
        assertTrue(runCatching { store.restoreBackup(uriFor(unsafe)) }.isFailure)
        assertEquals("保留的条目", store.load().entries.single().title)
        assertFalse(File(context.cacheDir.parentFile, "escape.txt").exists())
        store.cleanupUnused(listOf(draft))
        assertTrue(File(draft.path).isFile)
        store.cleanupUnused()
        assertFalse(File(draft.path).exists())
    }

    // Real content:// streams exercise resolver IO. AndroidX FileProvider assumes Unix separators
    // when matching roots, so its implementation cannot be used on this Windows JVM test host.
    private fun uriFor(file: File): Uri = provider.add(authority, file)

    private class StreamProvider : ContentProvider() {
        private val files = mutableMapOf<String, File>()
        fun add(authority: String, file: File): Uri {
            val id = newId()
            files[id] = file
            return Uri.parse("content://$authority/$id")
        }
        private fun file(uri: Uri): File = files[uri.lastPathSegment] ?: error("Unknown fixture URI")
        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = if (file(uri).extension == "txt") "text/plain" else "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> when (column) {
                    OpenableColumns.DISPLAY_NAME -> file(uri).name
                    OpenableColumns.SIZE -> file(uri).length()
                    else -> null
                } })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        val prefsName = "runtime-test-${root.name}"
        private val databases = mutableListOf<SQLiteDatabase>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "db/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory).also { databases += it }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler).also { databases += it }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefsName, mode)
        fun closeDatabases() { databases.forEach { if (it.isOpen) it.close() }; databases.clear() }
    }
}
