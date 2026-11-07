package com.collection

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.collection.domain.*
import com.collection.data.PreferencesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppFlowTest {
    @Test fun droppedTagOrderPersistsWithoutChangingTheActiveFilter() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        vm.addTag("甲"); vm.addTag("乙"); vm.addTag("丙")
        await { vm.library.tags.size == 3 }
        val ids = vm.library.tags.map { it.id }
        vm.updateHome(HomePreferences(tagIds = setOf(ids[1])))
        val reordered = listOf(ids[2], ids[0], ids[1])
        vm.reorderTags(reordered)
        assertFalse(vm.busy)
        await { vm.library.tags.map { it.id } == reordered }
        vm.reload(); await { !vm.loading }
        assertEquals(reordered, vm.library.tags.map { it.id })
        assertEquals(setOf(ids[1]), vm.home.tagIds)
    }
    @Test fun rapidTagChangesQueueWithoutBlockingOrOpeningDialogs() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        vm.newEntry(); vm.changeDraft(vm.draft.copy(title = "标签连续操作")); vm.saveEntry(); await { !vm.busy }
        val id = vm.library.entries.single().id
        vm.addTagToEntry(id, "标签一"); vm.addTagToEntry(id, "标签二")
        assertFalse(vm.busy)
        await { vm.library.entries.single().tagIds.size == 2 }
        val first = vm.library.tags.first()
        val second = vm.library.tags.last()
        vm.toggleEntryTag(id, first.id); vm.toggleEntryTag(id, second.id); vm.toggleEntryTag(id, first.id)
        assertFalse(vm.busy)
        await { vm.library.entries.single().tagIds == setOf(first.id) }
        vm.deleteTag(first)
        assertFalse(vm.busy)
        await { vm.library.tags.size == 1 && vm.library.entries.single().tagIds.isEmpty() }
        assertNull(vm.operationError)
        vm.reload(); await { !vm.loading }
        assertEquals(listOf(second.id), vm.library.tags.map { it.id })
        assertTrue(vm.library.entries.single().tagIds.isEmpty())
    }
    @Test fun batchDeletionRemovesOnlyChosenEntries() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        repeat(3) { index ->
            vm.newEntry(); vm.changeDraft(vm.draft.copy(title = "条目$index")); vm.saveEntry(); await { !vm.busy }
        }
        val keep = vm.library.entries.last().id
        vm.deleteEntries(vm.library.entries.filterNot { it.id == keep }.map { it.id }.toSet())
        await { !vm.busy }
        assertEquals(listOf(keep), vm.library.entries.map { it.id })
        vm.reload(); await { !vm.loading }
        assertEquals(listOf(keep), vm.library.entries.map { it.id })
    }
    private lateinit var app: Application
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.databaseList().forEach { app.deleteDatabase(it) }
        app.getSharedPreferences("collfresh_settings", 0).edit().clear().commit()
    }
    private fun await(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("Timed out waiting for app state")
    }
    @Test fun manualEntryCrudAndMissingKeyFailurePreservesEdits() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        assertNull(vm.loadError)
        vm.newEntry()
        vm.changeDraft(vm.draft.copy(title = "手动标题", summary = "手写总结", body = "原始笔记"), true)
        vm.generate()
        await { !vm.generating }
        assertTrue(vm.operationError!!.contains("API Key"))
        assertEquals("手动标题", vm.draft.title)
        assertEquals("手写总结", vm.draft.summary)
        vm.clearError()
        vm.saveEntry()
        await { !vm.busy }
        assertNull(vm.operationError)
        assertEquals(Page.DETAIL, vm.page)
        val saved = vm.library.entries.single()
        assertEquals("原始笔记", saved.body)
        vm.editEntry(saved)
        vm.changeDraft(vm.draft.copy(summary = "修改后的总结"), true)
        vm.saveEntry()
        await { !vm.busy }
        assertEquals(saved.id, vm.library.entries.single().id)
        assertEquals("修改后的总结", vm.library.entries.single().summary)
        vm.deleteEntry(vm.library.entries.single())
        await { !vm.busy }
        assertEquals(Page.HOME, vm.page)
        assertTrue(vm.library.entries.isEmpty())
    }
    @Test fun textOnlyShareOpensDraftWithoutSavingPrematurely() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        vm.receiveShare(SharePayload("从系统分享的文字", emptyList()))
        vm.acceptShare(false)
        await { !vm.busy }
        assertEquals(Page.EDITOR, vm.page)
        assertEquals("从系统分享的文字", vm.draft.body)
        assertTrue(vm.library.entries.isEmpty())
        vm.saveEntry()
        await { !vm.busy }
        assertEquals("从系统分享的文字", vm.library.entries.single().body)
    }
    @Test fun sendMultipleReadsStreamListAndDeduplicatesClipUris() {
        val first = Uri.parse("content://source/one")
        val second = Uri.parse("content://source/two")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            putExtra(Intent.EXTRA_TEXT, "多文件共享说明")
            clipData = ClipData.newRawUri("one", first)
        }
        val payload = readSharePayload(intent)!!
        assertEquals(listOf(first, second), payload.uris)
        assertEquals("多文件共享说明", payload.text)
    }
    @Test fun normalLaunchIgnoredAndPrivateFileShareRejected() {
        assertNull(readSharePayload(Intent(Intent.ACTION_MAIN)))
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/com.collection/private"))
        assertNull(readSharePayload(intent))
        assertEquals("文字", readSharePayload(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "文字"))!!.text)
    }
    @Test fun inlineDetailUpdatesPersistWithoutAnEditorAndAiFailureKeepsAllTitles() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        vm.newEntry(); vm.changeDraft(vm.draft.copy(title = "条目", body = "原始内容")); vm.saveEntry()
        await { !vm.busy }
        val saved = vm.library.entries.single()
        vm.addTagToEntry(saved.id, "就地标签"); await { vm.library.tags.isNotEmpty() && vm.library.entries.single().tagIds.isNotEmpty() }
        val updated = vm.library.entries.single().copy(title = "点击修改的标题", iconKey = "link", bodyTitle = "手动原文标题")
        vm.updateStoredEntry(updated); await { !vm.busy }
        assertEquals(Page.DETAIL, vm.page)
        assertEquals("点击修改的标题", vm.library.entries.single().title)
        vm.generateStoredTitle(saved.id)
        await { !vm.generating }
        assertTrue(vm.operationError!!.contains("API Key"))
        assertEquals("手动原文标题", vm.library.entries.single().bodyTitle)
        assertEquals("点击修改的标题", vm.library.entries.single().title)
        vm.clearError(); vm.reload(); await { !vm.loading }
        assertEquals("手动原文标题", vm.library.entries.single().bodyTitle)
        assertEquals("link", vm.library.entries.single().iconKey)
        assertEquals(setOf(vm.library.tags.single().id), vm.library.entries.single().tagIds)
    }
    @Test fun stateFlowSnapshotsAreImmutableAndThemeChoicePersists() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        val previous = vm.uiState.value
        vm.updateQuery("新查询")
        assertEquals("", previous.query)
        assertEquals("新查询", vm.uiState.value.query)
        vm.updateAppearance(AppearanceSettings(themeMode = ThemeMode.DARK, uiScale = 0.9f))
        await { PreferencesStore(app).readAppearance().themeMode == ThemeMode.DARK }
        assertEquals(ThemeMode.DARK, vm.uiState.value.appearance.themeMode)
        assertEquals(0.9f, PreferencesStore(app).readAppearance().uiScale)
    }
    @Test fun rapidHomeChangesPersistNewestSnapshot() {
        val vm = AppViewModel(app)
        await { !vm.loading }
        vm.updateHome(HomePreferences(cards = false, sort = SortOrder.CREATED_ASC))
        vm.updateHome(HomePreferences(cards = true, sort = SortOrder.TITLE_DESC))
        val latest = HomePreferences(cards = false, sort = SortOrder.UPDATED_ASC, tagIds = setOf("selected"))
        vm.updateHome(latest)
        await { PreferencesStore(app).readHome() == latest }
        assertEquals(latest, vm.home)
    }
}
