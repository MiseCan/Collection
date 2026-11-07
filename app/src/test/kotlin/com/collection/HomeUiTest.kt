package com.collection

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.collection.domain.Entry
import com.collection.domain.HomePreferences
import com.collection.domain.Library
import com.collection.domain.SortOrder
import com.collection.domain.Tag
import com.collection.ui.CollTheme
import com.collection.ui.HomeScreen
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
class HomeUiTest {
    @Test fun stacksStayCenteredAtEveryCountAndFirstPhotoCoversTheOthers() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)
        val photos = colors.mapIndexed { index, color ->
            val file = File(app.cacheDir, "ordered-stack-$index.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            com.collection.domain.Attachment(id = "layer$index", name = file.name, mime = "image/png", path = file.path, size = file.length())
        }
        var count by mutableStateOf(1)
        compose.setContent { CollTheme { com.collection.ui.EntryOriginalStack(Entry(id = "centered", attachments = photos.take(count))) } }
        for (size in 1..3) {
            compose.runOnIdle { count = size }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("图片缩略图", useUnmergedTree = true).fetchSemanticsNodes().size == size }
            val box = compose.onNodeWithTag("stack_container_centered", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithTag("stack_centered_0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithTag("stack_centered_${size - 1}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(box.center.y, (first.top + last.bottom) / 2, 1f)
            assertEquals(box.center.x, (first.left + last.right) / 2, 1f)
            assertEquals((size - 1) * 4f, last.top - first.top, 1f)
            val rendered = compose.onNodeWithTag("stack_container_centered", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            assertEquals(android.graphics.Color.RED, rendered.getPixel(28, 28))
            if (size == 3) {
                assertEquals(android.graphics.Color.GREEN, rendered.getPixel(28, 46))
                assertEquals(android.graphics.Color.BLUE, rendered.getPixel(28, 50))
            }
        }
    }

    @Test fun homeTagsWrapAndViewSwitchLivesInTheToolbar() {
        val tags = (1..8).map { Tag("wrap$it", "资料分类$it") }
        var preferences by mutableStateOf(HomePreferences())
        compose.setContent { CollTheme { HomeScreen(library.copy(tags = tags), preferences, "", {}, { preferences = it }, {}, {}, {}) } }
        val first = compose.onNodeWithText(tags.first().name).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText(tags.last().name).fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        val toolbar = compose.onNodeWithTag("home_app_bar").fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithContentDescription("切换为两列视图").fetchSemanticsNode().boundsInRoot
        assertTrue(switch.center.y < toolbar.bottom)
        compose.onNodeWithContentDescription("批量管理").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换为两列视图").performClick()
        compose.runOnIdle { assertTrue(!preferences.cards) }
        saveScreenshot("home-wrapping-tags")
    }

    @Test fun homeOriginalStackShowsOnlyThreeLayersAndLoadsPhotoThumbnails() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.cacheDir, "stack-photo.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(230, 175, 80))
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawCircle(36f, 30f, 18f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(45, 105, 80) })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val originals = (0..3).map { com.collection.domain.Attachment(id = "photo$it", name = "照片$it.png", mime = "image/png", path = file.path, size = file.length()) }
        val entry = Entry(id = "stack", title = "资料与网页", body = "https://developer.android.com", attachments = originals)
        compose.setContent { CollTheme { HomeScreen(Library(entries = listOf(entry)), HomePreferences(), "", {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("stack_stack_0", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stack_stack_1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stack_stack_2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("stack_stack_3", useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("图片缩略图", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        saveScreenshot("home-original-stack")
    }
    @Test fun longPressStartsSelectionAndBulkDeleteReportsOnlySelectedIds() {
        var deleted = emptySet<String>()
        compose.setContent { CollTheme { HomeScreen(library, HomePreferences(), "", {}, {}, {}, {}, {}, onDeleteEntries = { deleted = it }) } }
        compose.onNodeWithTag("entry_meeting").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithTag("home_batch_toolbar").assertIsDisplayed()
        compose.onNodeWithText("已选 1").assertExists()
        saveScreenshot("home-floating-batch")
        compose.onNodeWithContentDescription("删除").performClick()
        compose.onNodeWithText("删除所选条目？").assertExists()
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertEquals(setOf("meeting"), deleted) }
        compose.onNodeWithTag("home_batch_toolbar").assertDoesNotExist()
    }
    @Test fun selectionShrinksCardsFromTheLeftWithoutMovingTheOutgoingFab() {
        var preferences by mutableStateOf(HomePreferences())
        val compact = Library(entries = listOf(Entry(id = "compact", title = "资料")))
        compose.setContent { CollTheme { HomeScreen(compact, preferences, "", {}, { preferences = it }, {}, {}, {}) } }
        for (singleColumn in listOf(true, false)) {
            compose.runOnIdle { preferences = preferences.copy(cards = singleColumn) }
            compose.waitForIdle()
            val card = compose.onNodeWithTag("entry_compact")
            val before = card.fetchSemanticsNode().boundsInRoot
            val fabCenter = compose.onNodeWithTag("home_create_fab").fetchSemanticsNode().boundsInRoot.center
            compose.mainClock.autoAdvance = false
            try {
                card.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
                compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(48)
                val middle = card.fetchSemanticsNode().boundsInRoot
                assertTrue("卡片左边缘应平滑向右收缩: before=$before, middle=$middle", middle.left > before.left && middle.left < before.left + 56f)
                assertEquals("卡片右边缘应保持固定", before.right, middle.right, 1f)
                val fadingFab = compose.onNodeWithTag("home_create_fab").fetchSemanticsNode().boundsInRoot.center
                assertEquals("按钮淡出时不应被批量栏向上顶", fabCenter.y, fadingFab.y, 1f)
                assertEquals(fabCenter.x, fadingFab.x, 1f)
                compose.mainClock.advanceTimeBy(2000)
                val after = card.fetchSemanticsNode().boundsInRoot
                val checkbox = compose.onNodeWithTag("entry_select_compact").fetchSemanticsNode().boundsInRoot
                assertTrue("选择框应在卡片外侧左边", checkbox.right <= after.left)
                assertEquals(before.right, after.right, 1f)
                assertEquals(before.left + 56f, after.left, 1f)
                assertEquals(before.height, after.height, 1f)
                saveScreenshot(if (singleColumn) "home-select-left-single" else "home-select-left-grid")
                compose.onNodeWithTag("entry_select_compact").assertIsOn().performClick()
                compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("entry_select_compact").assertIsOff()
                compose.onNodeWithContentDescription("完成").performClick()
                compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(2000)
                assertEquals(before.left, card.fetchSemanticsNode().boundsInRoot.left, 1f)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun floatingBatchToolbarStaysVisibleWhileEntriesScroll() {
        val many = library.copy(entries = (1..30).map {
            Entry(id = "batch-$it", title = "资料 $it", createdAt = sampleTime, updatedAt = sampleTime)
        })
        compose.setContent { CollTheme { HomeScreen(many, HomePreferences(), "", {}, {}, {}, {}, {}) } }
        compose.onAllNodes(hasTestTag("entry_batch-1")).onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        val before = compose.onNodeWithTag("home_batch_toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("浮动工具栏应保持紧凑高度", before.height <= 80f)
        compose.onNodeWithText("收录新资料").assertDoesNotExist()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(25)
        val after = compose.onNodeWithTag("home_batch_toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        compose.onNodeWithContentDescription("全选").performClick()
        compose.onNodeWithText("已选 30").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消全选").performClick()
        compose.onNodeWithContentDescription("删除").assertIsNotEnabled()
        compose.onNodeWithContentDescription("完成").performClick()
        compose.onNodeWithTag("home_batch_toolbar").assertDoesNotExist()
        saveScreenshot("home-batch-finished")
        compose.onNodeWithText("收录新资料").assertIsDisplayed()
    }

    private val research = Tag("research", "论文")
    private val work = Tag("work", "工作")
    private val sampleTime = Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
    private val library = Library(
        entries = listOf(
            Entry(id = "meeting", title = "项目会议纪要", summary = "会议明确了下一轮交付计划。",
                tagIds = setOf(work.id), createdAt = sampleTime - 7200000, updatedAt = sampleTime),
            Entry(id = "reading", title = "文献阅读记录", summary = "两种实验方法的比较与局限。",
                tagIds = setOf(research.id, work.id), createdAt = sampleTime - 3600000, updatedAt = sampleTime - 1800000),
            Entry(id = "idea", title = "周末灵感", summary = "用卡片整理阅读收获。",
                tagIds = emptySet(), createdAt = sampleTime, updatedAt = sampleTime - 3600000),
        ),
        tags = listOf(research, work),
    )

    @Test
    fun emptyLibraryOffersWorkingCreateAction() {
        var createCalls = 0
        compose.setContent {
            CollTheme(dark = false) {
                HomeScreen(Library(), HomePreferences(), "", {}, {}, {}, { createCalls++ }, {})
            }
        }
        compose.onNodeWithText("给灵感留一页").assertDoesNotExist()
        compose.onNodeWithText("COLL · 个人资料库").assertDoesNotExist()
        compose.onNodeWithText("把零散资料，收进自己的知识里。").assertDoesNotExist()
        saveScreenshot("home-empty-light")
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, createCalls) }
    }

    @Test
    fun scrollingCollapsesTitleBarButKeepsActionsVisible() {
        val longLibrary = library.copy(entries = (1..30).map { index ->
            Entry(id = "item-$index", title = "资料 $index", summary = "用于滚动的资料")
        })
        compose.setContent {
            CollTheme(dark = false) {
                HomeScreen(longLibrary, HomePreferences(), "", {}, {}, {}, {}, {})
            }
        }
        val expanded = compose.onNodeWithTag("home_app_bar").fetchSemanticsNode().boundsInRoot
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performTouchInput { swipeUp() }
        compose.waitForIdle()
        val collapsed = compose.onNodeWithTag("home_app_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("滚动后标题栏应收缩", collapsed.height < expanded.height)
        assertEquals(expanded.top, collapsed.top, 1f)
        compose.onNodeWithContentDescription("打开设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索资料").assertIsDisplayed()
        compose.onNodeWithContentDescription("排序").assertIsDisplayed()
        compose.onNodeWithContentDescription("管理标签").assertDoesNotExist()
        saveScreenshot("home-collapsed-light")
    }

    @Test
    fun toolbarSearchReadsTitleAndClearRestoresResults() {
        var query by mutableStateOf("")
        var prefs by mutableStateOf(HomePreferences())
        compose.setContent {
            CollTheme(dark = false) {
                HomeScreen(library, prefs, query, { query = it }, { prefs = it }, {}, {}, {})
            }
        }
        saveScreenshot("home-populated-light")
        compose.onNodeWithContentDescription("搜索资料").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("文献")
        compose.onNodeWithText("文献阅读记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("项目会议纪要").assertDoesNotExist()
        compose.onNodeWithTag("home_tag_research").performClick()
        compose.runOnIdle { assertEquals(setOf(research.id), prefs.tagIds) }
        saveScreenshot("home-search-results")
        compose.onNodeWithContentDescription("清空搜索").assertIsDisplayed().performClick()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithTag("entry_reading")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithTag("home_batch_toolbar").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除").performClick()
        compose.onNodeWithText("删除所选条目？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("完成").performClick()
        compose.onNodeWithText("所有收录").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭搜索").performClick()
        compose.onNodeWithText("项目会议纪要").assertExists()
    }

    @Test
    fun selectingTwoTagsUsesAndMatchingAndCanReset() {
        var prefs by mutableStateOf(HomePreferences())
        compose.setContent {
            CollTheme(dark = false) {
                HomeScreen(library, prefs, "", {}, { prefs = it }, {}, {}, {})
            }
        }
        compose.onNodeWithText("论文", useUnmergedTree = true).performClick()
        compose.onNodeWithText("工作", useUnmergedTree = true).performClick()
        compose.onNodeWithText("项目会议纪要").assertDoesNotExist()
        compose.onNodeWithText("文献阅读记录").assertExists()
        compose.runOnIdle { assertEquals(setOf("research", "work"), prefs.tagIds) }
        compose.onNodeWithText("全部").performClick()
        compose.runOnIdle { assertTrue(prefs.tagIds.isEmpty()) }
    }

    @Test
    fun compactCardsRetainTagsAndAttachmentInfoButHideDateAndSummary() {
        val entry = library.entries.first().copy(attachments = listOf(com.collection.domain.Attachment(
            name = "原件.pdf", mime = "application/pdf", size = 10, path = "unused")))
        compose.setContent {
            CollTheme { HomeScreen(library.copy(entries = listOf(entry)), HomePreferences(), "", {}, {}, {}, {}, {}) }
        }
        val bounds = compose.onNodeWithTag("entry_meeting").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("紧凑卡片仍须容纳标题、附件和标签", bounds.height in 80f..88f)
        compose.onNodeWithText(entry.title).assertExists()
        compose.onNodeWithText("1 个原始附件").assertExists()
        compose.onNodeWithText("# 工作").assertExists()
        compose.onNodeWithText(entry.summary).assertDoesNotExist()
        compose.onNodeWithText("2026.", substring = true).assertDoesNotExist()
    }

    @Test
    fun sortAndViewControlsUpdatePreferences() {
        var prefs by mutableStateOf(HomePreferences())
        compose.setContent {
            CollTheme(dark = true) {
                HomeScreen(library, prefs, "", {}, { prefs = it }, {}, {}, {})
            }
        }
        saveScreenshot("home-populated-dark")
        compose.onNodeWithContentDescription("切换为两列视图").performClick()
        compose.runOnIdle { assertTrue(!prefs.cards) }
        compose.onNodeWithContentDescription("排序").performClick()
        compose.onNodeWithText("标题 · 升序").performClick()
        compose.runOnIdle { assertEquals(SortOrder.TITLE_ASC, prefs.sort) }
    }

    @Test
    fun twoColumnsPlaceTwoEntryCardsOnTheSameRow() {
        var prefs by mutableStateOf(HomePreferences())
        compose.setContent { CollTheme { HomeScreen(library, prefs, "", {}, { prefs = it }, {}, {}, {}) } }
        compose.onNodeWithContentDescription("切换为两列视图").performClick()
        val first = compose.onNodeWithTag("entry_meeting").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("entry_reading").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left > first.left)
        compose.onNodeWithContentDescription("切换为单列视图").assertExists()
        saveScreenshot("home-two-columns")
    }

    @Test
    fun longPressDragsTagsAcrossWrappedRowsWithoutAMenuOrFilterChange() {
        var prefs by mutableStateOf(HomePreferences())
        val tags = (1..7).map { Tag("drag$it", "资料分类$it") }
        var reordered: List<String>? = null
        compose.setContent { CollTheme {
            HomeScreen(library.copy(tags = tags), prefs, "", {}, { prefs = it }, {}, {}, {},
                onReorderTags = { reordered = it })
        } }
        val group = compose.onNodeWithTag("home_tag_filters").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("home_tag_drag1").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("home_tag_drag7").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        compose.onNodeWithTag("home_tag_filters").performTouchInput { down(last.center - group.topLeft) }
        compose.mainClock.advanceTimeBy(700)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        compose.waitForIdle()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.onNodeWithTag("home_tag_filters").performTouchInput { moveTo(first.center - group.topLeft); up() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("drag7") + (1..6).map { "drag$it" }, reordered)
            assertTrue(prefs.tagIds.isEmpty())
        }
        saveScreenshot("home-dragged-tags")
    }

    @Test fun holdingATagWithoutDraggingDoesNotSelectOrOpenAMenu() {
        var prefs by mutableStateOf(HomePreferences())
        var commits = 0
        compose.setContent { CollTheme {
            HomeScreen(library, prefs, "", {}, { prefs = it }, {}, {}, {}, onReorderTags = { commits++ })
        } }
        compose.onNodeWithTag("home_tag_work").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        compose.onNodeWithTag("home_tag_work").performTouchInput { up() }
        compose.runOnIdle { assertTrue(prefs.tagIds.isEmpty()); assertEquals(0, commits) }
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.onNodeWithTag("home_tag_work").performClick()
        compose.runOnIdle { assertEquals(setOf("work"), prefs.tagIds) }
    }

    @Test fun largeFontCanGrowCardsInsteadOfClippingTagsAndTitles() {
        val one = library.copy(entries = listOf(library.entries.first().copy(title = "长标题资料与记录需要完整显示")))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)) {
                CollTheme { HomeScreen(one, HomePreferences(), "", {}, {}, {}, {}, {}) }
            }
        }
        val card = compose.onNodeWithTag("entry_meeting").fetchSemanticsNode().boundsInRoot
        assertTrue(card.height > 108f)
        compose.onNodeWithText("# 工作").assertIsDisplayed()
        saveScreenshot("home-font-200")
    }

    private fun saveScreenshot(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val image = (if (dialog) compose.onNode(isDialog()) else compose.onRoot())
            .captureToImage().asAndroidBitmap()
        val path = File("build/reports/screenshots/$name.png")
        path.parentFile?.mkdirs()
        path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}



