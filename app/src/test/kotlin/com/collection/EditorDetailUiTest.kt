package com.collection

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.collection.domain.Attachment
import com.collection.domain.Entry
import com.collection.domain.Tag
import com.collection.ui.CollTheme
import com.collection.ui.DetailScreen
import com.collection.ui.EditorScreen
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EditorDetailUiTest {
    @get:Rule val compose = createComposeRule()
    private val source = Attachment(id = "source", name = "阅读资料.pdf", mime = "application/pdf", size = 153600,
        path = "/test/originals/source.pdf")
    private val image = Attachment(id = "image", name = "课堂板书.png", mime = "image/png", size = 76800,
        path = "/test/originals/image.png")
    private val tag = Tag("research", "学习研究")
    private val timestamp = Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
    private val entry = Entry(
        id = "reading",
        title = "阅读记录与课堂笔记",
        summary = "汇集阅读资料与课堂板书，梳理研究方法和待验证的问题。",
        body = "今天读到的研究方法值得继续比较。\n下次实验前，先核对样本条件和测量步骤。",
        attachments = listOf(source, image), tagIds = setOf(tag.id),
        createdAt = timestamp, updatedAt = timestamp + 60000,
    )

    @Test
    @Config(qualifiers = "w412dp-h1320dp-mdpi")
    fun editorSectionsFollowRequestedOrder() {
        showEditor()
        val ordered = listOf(
            compose.onNodeWithText("标题", substring = false),
            compose.onNodeWithContentDescription("添加多个文件"),
            compose.onNodeWithText("已添加文件"),
            compose.onNodeWithText("文字与笔记"),
            compose.onNodeWithText("标签", substring = false),
            compose.onNodeWithText("保存条目"),
        )
        val tops = ordered.map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("标题、文件区、文件列表、正文、标签、保存按钮应依次向下排列", tops.zipWithNext().all { (first, second) -> first < second })
        saveScreenshot("editor-light-full")
    }

    @Test
    fun replacingManualTitleAndSummaryRequiresExplicitConfirmation() {
        var generationCalls = 0
        showEditor(onGenerate = { generationCalls++ }, aiEdited = true)
        compose.onNodeWithContentDescription("重新生成标题").performScrollTo().performClick()
        compose.onNodeWithText("替换已修改的内容？").assertExists()
        compose.runOnIdle { assertEquals(0, generationCalls) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, generationCalls) }
        compose.onNodeWithContentDescription("重新生成标题").performClick()
        compose.onNodeWithText("重新生成并替换").performClick()
        compose.runOnIdle { assertEquals(1, generationCalls) }
    }

    @Test
    fun attachmentCanPreviewThenRemoveWithoutChangingNotes() {
        var draft by mutableStateOf(entry)
        var previewed: Attachment? = null
        compose.setContent {
            CollTheme(dark = false) {
                EditorScreen(draft, listOf(tag), false, "", false,
                    onChange = { value, _ -> draft = value }, onBack = {}, onAddFiles = {},
                    onPreview = { previewed = it },
                    onRemove = { id -> draft = draft.copy(attachments = draft.attachments.filterNot { it.id == id }) },
                    onGenerate = {}, onCancelGeneration = {}, onAddTag = {}, onSave = {})
            }
        }
        compose.onNodeWithText(source.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(source, previewed) }
        compose.onNodeWithContentDescription("移除 ${source.name}").performClick()
        compose.onNodeWithText(source.name).assertDoesNotExist()
        compose.onNodeWithText(image.name).assertExists()
        compose.runOnIdle {
            assertEquals(entry.body, draft.body)
            assertEquals(listOf(image), draft.attachments)
        }
    }

    @Test
    fun entryIconControlsAreRemoved() {
        showEditor()
        compose.onNodeWithContentDescription("条目图标：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("标题", substring = false).assertIsDisplayed()
    }

    @Test
    fun manualEntryCanSaveWithoutAnAiResult() {
        var saved = false
        showEditor(draft = entry.copy(title = "", summary = ""), onSave = { saved = true })
        scrollToText("保存条目").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun tagsAreAddedInlineAndLongPressOffersADeleteMenu() {
        var current by mutableStateOf(entry)
        var tags by mutableStateOf(listOf(tag))
        compose.setContent { CollTheme {
            DetailScreen(current, tags, {}, {}, {}, onUpdate = { current = it },
                onAddTag = { name ->
                    val created = Tag("inline", name)
                    tags = tags + created; current = current.copy(tagIds = current.tagIds + created.id)
                }, onDeleteTag = { removed ->
                    tags = tags.filterNot { it.id == removed.id }; current = current.copy(tagIds = current.tagIds - removed.id)
                })
        } }
        compose.onNodeWithContentDescription("添加标签").performClick()
        compose.onNodeWithTag("inline_tag_name").performTextReplacement("  随手记  ")
        compose.onNodeWithContentDescription("创建标签").performClick()
        compose.onNodeWithText("随手记").assertIsSelected()
        compose.onNodeWithTag("inline_tag_name").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription("添加标签").performClick()
        compose.onNodeWithTag("inline_tag_name").performTextReplacement("随手记")
        compose.onNodeWithContentDescription("创建标签").assertIsNotEnabled()
        compose.onNodeWithText("标签已存在，请直接选择").assertExists()
        compose.onNodeWithContentDescription("取消添加标签").performClick()
        compose.onNodeWithTag("entry_tag_inline").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.runOnIdle { assertEquals(2, tags.size); assertTrue("inline" in current.tagIds) }
        compose.onAllNodes(isPopup()).assertCountEquals(1)
        compose.onNodeWithText("删除", substring = false).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(tag), tags); assertEquals(entry.tagIds, current.tagIds) }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("随手记").assertDoesNotExist()
    }

    @Test fun detailTagsWrapAndAddContentImmediatelyRequestsFiles() {
        val tags = listOf(tag) + (1..6).map { Tag("extra$it", "资料分类$it") }
        var files = 0
        compose.setContent { CollTheme { DetailScreen(entry, tags, {}, {}, {}, onAddFiles = { files++ }) } }
        val first = compose.onNodeWithTag("entry_tag_${tag.id}").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("entry_tag_extra6").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        compose.onNodeWithContentDescription("添加内容").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, files) }
        compose.onNodeWithText("文件", substring = false).assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        saveScreenshot("detail-wrapping-tags")
    }

    @Test
    fun detailShowsStoredSummaryNotesAndOriginalAttachmentAndCanEdit() {
        var previewed: Attachment? = null
        var updated: Entry? = null
        compose.setContent {
            CollTheme(dark = false) {
                DetailScreen(entry, listOf(tag), {}, {}, { previewed = it }, onUpdate = { updated = it })
            }
        }
        compose.onNodeWithText(entry.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("更换图标").assertDoesNotExist()
        compose.onNodeWithText("内容总结").assertDoesNotExist()
        compose.onNodeWithText(entry.summary).assertDoesNotExist()
        val card = compose.onNodeWithTag("original_text_card").performScrollTo().assertIsDisplayed()
        assertTrue(card.fetchSemanticsNode().boundsInRoot.height <= 132f)
        card.performClick()
        compose.onNodeWithContentDescription("关闭文字查看").assertIsDisplayed()
        compose.onNodeWithTag("original_text_editor").assertTextEquals(entry.body).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭文字查看").performClick()
        compose.onNodeWithText(source.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(source, previewed) }
        compose.onNodeWithText("编辑条目", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(entry.title).performClick()
        compose.onNodeWithTag("detail_title_input").performTextReplacement("就地修改标题")
        compose.onNodeWithContentDescription("保存标题").performClick()
        compose.runOnIdle { assertEquals("就地修改标题", updated!!.title) }
    }

    @Test
    fun longNotesStayCollapsedUntilTheirCardIsOpened() {
        val longText = "原始文字内容。".repeat(200) + "完整内容结尾标记"
        compose.setContent {
            CollTheme { DetailScreen(entry.copy(body = longText), listOf(tag), {}, {}, {}) }
        }
        compose.onNodeWithText("完整内容结尾标记", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("original_text_card").performScrollTo().performClick()
        compose.onNodeWithTag("original_text_editor").assertTextEquals(longText)
        compose.onNodeWithContentDescription("关闭文字查看").performClick()
        compose.onNodeWithTag("original_text_editor").assertDoesNotExist()
    }

    @Test
    fun deletingEntryRequiresConfirmation() {
        var deleteCalls = 0
        compose.setContent {
            CollTheme { DetailScreen(entry, listOf(tag), {}, { deleteCalls++ }, {}) }
        }
        compose.onNodeWithContentDescription("删除条目").performClick()
        compose.onNodeWithText("删除这个条目？").assertExists()
        compose.runOnIdle { assertEquals(0, deleteCalls) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deleteCalls) }
        compose.onNodeWithContentDescription("删除条目").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, deleteCalls) }
    }

    @Test
    fun originalCardsShowTheirOwnAiTitlesAndDecodeLocalImageThumbnail() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(context.cacheDir, "thumbnail.png")
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(53, 117, 112)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val original = image.copy(path = file.path, title = "课堂板书重点")
        val pdf = source.copy(title = "研究方法比较")
        compose.setContent {
            CollTheme { DetailScreen(entry.copy(bodyTitle = "实验准备事项", attachments = listOf(original, pdf)), listOf(tag), {}, {}, {}) }
        }
        compose.onNodeWithText("实验准备事项").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(entry.body).assertDoesNotExist()
        compose.onNodeWithText("课堂板书重点").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("图片缩略图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("研究方法比较").performScrollTo().assertIsDisplayed()
        saveScreenshot("detail-original-titles")
    }

    private fun showEditor(
        draft: Entry = entry,
        aiEdited: Boolean = false,
        onGenerate: () -> Unit = {},
        onAddTag: (String) -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        compose.setContent {
            CollTheme(dark = false) {
                EditorScreen(draft, listOf(tag), false, "", aiEdited,
                    onChange = { _, _ -> }, onBack = {}, onAddFiles = {}, onPreview = {}, onRemove = {},
                    onGenerate = onGenerate, onCancelGeneration = {}, onAddTag = onAddTag, onSave = onSave)
            }
        }
    }

    @Test fun tagChipsToggleDirectlyAndSourceBatchRemovalKeepsUnselectedFiles() {
        var current by mutableStateOf(entry.copy(tagIds = emptySet()))
        compose.setContent { CollTheme { DetailScreen(current, listOf(tag), {}, {}, {}, onUpdate = { current = it }) } }
        compose.onNodeWithText(tag.name).performClick()
        compose.runOnIdle { assertTrue(tag.id in current.tagIds) }
        compose.onNodeWithText(tag.name).performClick()
        compose.runOnIdle { assertTrue(current.tagIds.isEmpty()) }
        compose.onNodeWithText("批量管理").performScrollTo().performClick()
        compose.onNodeWithTag("original_text_card").performScrollTo().performClick()
        compose.onNodeWithText("已选 1").assertExists()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除所选原始内容？").assertExists()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.runOnIdle {
            assertEquals("", current.body)
            assertEquals(entry.attachments, current.attachments)
        }
        saveScreenshot("detail-batch-tags")
    }

    @Test fun longPressCanGenerateOnlyOneSourceAndLinksPrecedeOriginalContent() {
        val requested = mutableListOf<String?>()
        val linked = entry.copy(body = "阅读 https://developer.android.com/guide", bodyTitle = "", attachments = listOf(source))
        compose.setContent {
            CollTheme { DetailScreen(linked, listOf(tag), {}, {}, {}, onGenerateSource = { requested += it }) }
        }
        val link = compose.onNodeWithText("developer.android.com").performScrollTo().fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText("原始内容").fetchSemanticsNode().boundsInRoot
        assertTrue(link.top < heading.top)
        compose.onNodeWithTag("original_text_card").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("AI 生成标题").performClick()
        compose.runOnIdle { assertEquals(listOf<String?>(null), requested) }
        compose.onNodeWithText(source.name).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithText("AI 生成标题").performClick()
        compose.runOnIdle { assertEquals(listOf(null, source.id), requested) }
        saveScreenshot("detail-inline-actions")
    }

    @Test fun sourceTitlesAreEditableWithoutRenamingOrReplacingOriginalFiles() {
        var draft by mutableStateOf(entry)
        compose.setContent {
            CollTheme { EditorScreen(draft, listOf(tag), false, "", false,
                onChange = { changed, _ -> draft = changed }, onBack = {}, onAddFiles = {}, onPreview = {},
                onRemove = {}, onGenerate = {}, onCancelGeneration = {}, onAddTag = {}, onSave = {}) }
        }
        compose.onNodeWithContentDescription("修改标题 ${source.name}").performScrollTo().performClick()
        compose.onNodeWithText("原件标题").performScrollTo().performTextReplacement("手动原件标题")
        compose.onNodeWithContentDescription("完成标题修改").performClick()
        compose.onNodeWithText("手动原件标题").assertExists()
        compose.onNodeWithText("原始文字标题").performScrollTo().performTextReplacement("手动笔记标题")
        compose.runOnIdle {
            assertEquals("手动笔记标题", draft.bodyTitle)
            assertEquals(source.copy(title = "手动原件标题"), draft.attachments.first())
            assertEquals(entry.body, draft.body)
        }
        saveScreenshot("editor-source-titles")
    }

    @Test fun detectedLinkCardShowsDomainAndOpensTheFullAddress() {
        var opened = ""
        val linked = entry.copy(body = "阅读 https://developer.android.com/guide?hl=zh-cn，稍后整理。", attachments = emptyList())
        compose.setContent {
            CollTheme { DetailScreen(linked, listOf(tag), {}, {}, {}, onOpenLink = { opened = it }) }
        }
        compose.onNodeWithText("developer.android.com").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("https://developer.android.com/guide?hl=zh-cn", opened) }
        compose.onNodeWithTag("original_text_card").assertExists()
        saveScreenshot("detail-compact-links")
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val path = File("build/reports/screenshots/$name.png")
        path.parentFile?.mkdirs()
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
}

