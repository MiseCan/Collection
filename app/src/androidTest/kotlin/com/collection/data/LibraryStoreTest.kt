package com.collection.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.collection.domain.AiConfig
import com.collection.domain.Entry
import com.collection.domain.HomePreferences
import com.collection.domain.newId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uses isolated storage beneath the provider's allowed originals path, never the real library DB. */
@RunWith(AndroidJUnit4::class)
class LibraryStoreTest {
    private lateinit var base: Context
    private lateinit var context: IsolatedContext
    private lateinit var root: File
    private lateinit var store: LibraryStore

    @Before fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.filesDir, "originals/storage-test-${newId()}").apply { mkdirs() }
        context = IsolatedContext(base, root)
        store = LibraryStore(context)
    }

    @After fun tearDown() {
        // Only the generated test subtree is removed.
        check(root.canonicalFile.parentFile == File(base.filesDir, "originals").canonicalFile)
        root.deleteRecursively()
        base.deleteSharedPreferences(context.prefsName)
    }

    @Test fun entriesAndTagsPersistAndTagDeletionClearsAssociationsAndFilters() = runBlocking {
        val tag = store.addTag("  工作  ")
        assertEquals(tag.id, store.addTag("工作").id)
        val entry = Entry(title = "会议", summary = "讨论发布计划", body = "原始笔记", tagIds = setOf(tag.id))
        store.saveEntry(entry)
        store.renameTag(tag.id, "计划")
        val reopened = LibraryStore(context)
        assertEquals("计划", reopened.load().tags.single().name)
        assertEquals("原始笔记", reopened.load().entries.single().body)
        PreferencesStore(context).writeHome(HomePreferences(tagIds = setOf(tag.id)))
        store.deleteTag(tag.id)
        assertTrue(reopened.load().entries.single().tagIds.isEmpty())
        assertTrue(PreferencesStore(context).readHome().tagIds.isEmpty())
        store.deleteEntry(entry.id)
        assertTrue(reopened.load().entries.isEmpty())
    }

    @Test fun textOriginalRoundTripsThroughBackupAndMergeWithoutOverwritingLocalEntries() = runBlocking {
        val originalText = "保留原始文字\n第二行，带中文。\n"
        val source = File(root, "source.txt").apply { writeText(originalText) }
        val attachment = store.importAttachment(uriFor(source))
        assertEquals("source.txt", attachment.name)
        assertArrayEquals(source.readBytes(), File(attachment.path).readBytes())
        val tag = store.addTag("资料")
        val entry = Entry(title = "资料条目", summary = "完整原文", attachments = listOf(attachment), tagIds = setOf(tag.id))
        store.saveEntry(entry)
        val backup = File(root, "library.zip").apply { createNewFile() }
        store.exportBackup(uriFor(backup))
        store.restoreBackup(uriFor(backup))
        val restored = LibraryStore(context).load()
        assertEquals(2, restored.entries.size)
        assertEquals(1, restored.tags.size)
        assertTrue(restored.entries.any { it.id == entry.id })
        val imported = restored.entries.single { it.id != entry.id }
        assertNotEquals(attachment.path, imported.attachments.single().path)
        assertArrayEquals(source.readBytes(), File(imported.attachments.single().path).readBytes())
        assertEquals(setOf(tag.id), imported.tagIds)
    }

    @Test fun unsafeArchiveIsRejectedWithoutChangingTheLibrary() = runBlocking {
        store.saveEntry(Entry(title = "保持此条目"))
        val unsafe = File(root, "unsafe.zip")
        ZipOutputStream(unsafe.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.txt")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        assertTrue(runCatching { store.restoreBackup(uriFor(unsafe)) }.isFailure)
        assertEquals("保持此条目", store.load().entries.single().title)
        assertFalse(File(context.cacheDir.parentFile, "escape.txt").exists())
    }

    @Test fun draftFilesSurviveOtherEntryDeletesUntilExplicitCleanup() = runBlocking {
        val source = File(root, "draft.txt").apply { writeText("draft") }
        val draft = store.importAttachment(uriFor(source))
        val entry = Entry(title = "另一个条目")
        store.saveEntry(entry)
        store.deleteEntry(entry.id)
        assertTrue(File(draft.path).exists())
        store.cleanupUnused(listOf(draft))
        assertTrue(File(draft.path).exists())
        store.cleanupUnused()
        assertFalse(File(draft.path).exists())
    }

    @Test fun credentialsPersistEncryptedAndNeverAppearInBackup() = runBlocking {
        val key = "test-only-${newId()}"
        PreferencesStore(context).writeAi(AiConfig(apiKey = key))
        assertEquals(key, PreferencesStore(context).readAi().apiKey)
        val values = base.getSharedPreferences(context.prefsName, Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertTrue(values.none { it.contains(key) })
        val backup = File(root, "empty.zip").apply { createNewFile() }
        store.exportBackup(uriFor(backup))
        java.util.zip.ZipInputStream(backup.inputStream()).use { zip ->
            while (zip.nextEntry != null) assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains(key))
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(base, "${base.packageName}.files", file)

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        val prefsName = "test-${root.name}"
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "db/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefsName, mode)
    }
}
