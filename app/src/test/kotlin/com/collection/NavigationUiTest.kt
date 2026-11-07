package com.collection

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.collection.ui.CollectionNavigator
import androidx.test.core.app.ApplicationProvider
import com.collection.domain.PageTransitionStyle
import com.collection.domain.ThemeMode
import com.collection.ui.AppRoot
import com.collection.ui.AppRoutes
import com.collection.ui.CollTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.sqrt

/** Runs the real NavHost/StateFlow/lifecycle route wiring, not a static screen mock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: AppViewModel
    private lateinit var nav: CollectionNavigator
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.databaseList().forEach { app.deleteDatabase(it) }
        app.getSharedPreferences("collfresh_settings", 0).edit().clear().commit()
        vm = AppViewModel(app)
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.loading) return@repeat
            Thread.sleep(5)
        }
        assertFalse(vm.loading)
        nav = CollectionNavigator()
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val ui by vm.uiState.collectAsStateWithLifecycle()
            val dark = when (ui.appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CollTheme(dark = dark, appearance = ui.appearance) { AppRoot(vm, nav) }
        }
        compose.waitForIdle()
    }
    private fun waitForRoute(route: String) { try { compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); nav.currentDestination?.route == route && !vm.busy && !vm.loading } } catch (e: Throwable) { throw AssertionError("Expected $route, actual ${nav.currentDestination?.route}, VM ${vm.page}, lifecycle local", e) }; compose.waitForIdle() }

    @Test fun createSaveAndBackUseRealBackStackWithoutReturningToSavedEditor() {
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        compose.runOnIdle { vm.changeDraft(vm.draft.copy(title = "导航测试条目", body = "原始笔记"), true) }
        compose.onNodeWithText("保存条目").performScrollTo().performClick()
        waitForRoute(AppRoutes.DETAIL)
        screenshot("nav-detail")
        assertEquals(vm.library.entries.single().id, nav.currentBackStackEntry?.arguments?.getString("entryId"))
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithText("导航测试条目").assertExists()
        screenshot("nav-home")
    }

    @Test fun discardConfirmationGuardsEditorAndSettingsReturnsHome() {
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        compose.runOnIdle { vm.changeDraft(vm.draft.copy(body = "尚未保存的内容")) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃本次编辑？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals(AppRoutes.EDITOR, nav.currentDestination?.route)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃编辑").performClick()
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("设置与备份").assertDoesNotExist()
        compose.onNodeWithText("为资料，找到重点").assertDoesNotExist()
        compose.onNodeWithText("设置").assertExists()
        compose.onNodeWithText("主题设置").assertExists()
        screenshot("nav-settings")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.HOME)
    }

    @Test fun tappingDetailTitleSavesInPlaceWithoutOpeningAnEditor() {
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        compose.runOnIdle { vm.changeDraft(vm.draft.copy(title = "原来的标题", body = "原始资料")); vm.saveEntry() }
        waitForRoute(AppRoutes.DETAIL)
        val destinationId = nav.currentBackStackEntry!!.id
        compose.onNodeWithText("原来的标题").performClick()
        compose.onNodeWithTag("detail_title_input").performTextReplacement("直接改好的标题")
        compose.onNodeWithContentDescription("保存标题").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.busy && vm.library.entries.single().title == "直接改好的标题" }
        compose.waitForIdle()
        assertEquals(destinationId, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("编辑条目").assertDoesNotExist()
        compose.onNodeWithText("直接改好的标题").assertExists()
        screenshot("detail-direct-save")
    }

    @Test fun originalTextUsesTheSamePageStackAndReturnsToItsEntry() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking {
            com.collection.data.LibraryStore(app).saveEntry(com.collection.domain.Entry(
                id = "viewer-entry", title = "预览导航", body = "完整原文", bodyTitle = "原文标题"))
        }
        compose.runOnIdle { vm.reload() }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.loading }
        compose.runOnIdle { vm.openEntry(vm.library.entries.single()) }
        waitForRoute(AppRoutes.DETAIL)
        compose.onNodeWithTag("original_text_card").performScrollTo().performClick()
        waitForRoute(AppRoutes.TEXT)
        compose.onNodeWithTag("page_text").assertExists()
        compose.onNodeWithText("完整原文").assertExists()
        compose.onNodeWithContentDescription("编辑").assertDoesNotExist()
        compose.onNodeWithTag("original_text_editor").performTextReplacement("在原文页直接编辑的新内容")
        compose.onNodeWithContentDescription("保存文字").performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.busy && vm.library.entries.single().body == "在原文页直接编辑的新内容" }
        compose.waitForIdle()
        assertEquals(AppRoutes.TEXT, nav.currentDestination?.route)
        screenshot("nav-text-embedded")
        compose.runOnIdle { back.onBackPressed() }
        waitForRoute(AppRoutes.DETAIL)
        assertEquals("viewer-entry", nav.currentBackStackEntry?.arguments?.getString("entryId"))
    }

    @Test fun predictiveSettingsBackCanCancelAndThenReturnToHome() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(160f, 400f, .5f, 0))
        }
        compose.waitForIdle()
        assertEquals(AppRoutes.SETTINGS, nav.currentDestination?.route)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("设置").assertExists()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(300f, 400f, .85f, 0))
            back.onBackPressed()
        }
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithText("设置").assertDoesNotExist()
    }

    @Test fun themeSettingsApplyPaletteModeScaleAndTransition() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("主题设置").performClick()
        waitForRoute(AppRoutes.THEME)
        compose.onNodeWithText("色彩风格").assertExists()
        compose.onNodeWithText("色彩标准").assertExists()
        compose.onNodeWithContentDescription("深色").performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, vm.uiState.value.appearance.themeMode)
        compose.onNodeWithTag("seed_color_2").performClick()
        compose.waitForIdle()
        assertEquals(0xFFE85D75, vm.uiState.value.appearance.seedColor)
        val list = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("page_theme")))
        list.performScrollToNode(hasText("预测性返回手势"))
        compose.onNodeWithText("预测性返回手势").assertExists()
        compose.onNodeWithText("横滑返回手势").assertExists()
        list.performScrollToNode(hasText("页面切换手势"))
        compose.onNodeWithText("页面切换手势").assertExists()
        list.performScrollToNode(hasText("Miuix（iOS 风格）"))
        compose.onNodeWithText("Miuix（iOS 风格）").performClick()
        compose.onNodeWithText("淡入淡出").performClick()
        compose.waitForIdle()
        assertEquals(PageTransitionStyle.FADE, vm.uiState.value.appearance.pageTransition)
        list.performScrollToNode(hasText("界面缩放"))
        compose.onNodeWithText("界面缩放").assertExists()
        compose.onNodeWithTag("theme_ui_scale").assertExists()
        screenshot("theme-settings")
        list.performScrollToIndex(0)
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.SETTINGS)
    }

    @Test fun themeToolbarStaysPinnedAndPredictiveBackUsesTheSettingsParent() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("主题设置").performClick()
        waitForRoute(AppRoutes.THEME)
        screenshot("theme-unified-toolbar")
        val list = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("page_theme")))
        list.performScrollToNode(hasText("界面缩放"))
        compose.onNodeWithTag("theme_app_bar").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        screenshot("theme-toolbar-pinned")
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(160f, 400f, .5f, 0))
        }
        compose.waitForIdle()
        assertEquals(AppRoutes.THEME, nav.currentDestination?.route)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme_app_bar").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.runOnIdle { back.onBackPressed() }
        waitForRoute(AppRoutes.HOME)
    }

    @Test fun predictiveTransitionPaintsBeforeReleaseAndRestoresAfterCancel() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        val atRest = capture()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(160f, 400f, .5f, 0))
        }
        compose.waitForIdle()
        // Still on Settings: this is the live predictive frame, not a post-release pop.
        assertEquals(AppRoutes.SETTINGS, nav.currentDestination?.route)
        compose.onNodeWithText("设置").assertExists()
        val midGesture = capture()
        assertTrue(
            "The settings page must visibly follow the finger before release",
            pixelDifference(atRest, midGesture) > 0.02f,
        )
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(AppRoutes.SETTINGS, nav.currentDestination?.route)
        assertTrue(
            "Cancellation must restore the original frame",
            pixelDifference(atRest, capture()) < 0.01f,
        )
    }

    @Test fun unsavedSettingsUsesPredictiveProgressAndOnlyLeavesAfterConfirmation() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("AI 连接").performScrollTo().performClick()
        waitForRoute(AppRoutes.AI)
        compose.onNode(hasSetTextAction() and hasText("deepseek-flash"))
            .performScrollTo().performTextReplacement("deepseek-predictive")
        compose.waitForIdle()
        val atRest = capture()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(170f, 400f, .55f, 0))
        }
        compose.waitForIdle()
        assertEquals(AppRoutes.AI, nav.currentDestination?.route)
        assertTrue("Unsaved settings must animate with the system gesture", pixelDifference(atRest, capture()) > 0.02f)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("deepseek-predictive").assertExists()
        compose.onNodeWithText("放弃未保存的设置？").assertDoesNotExist()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(300f, 400f, .9f, 0))
            back.onBackPressed()
        }
        compose.waitForIdle()
        // The gesture completing only opens the confirmation; it must not pop by itself.
        assertEquals(AppRoutes.AI, nav.currentDestination?.route)
        compose.onNodeWithText("放弃未保存的设置？").assertExists()
        compose.onNodeWithText("放弃修改").performClick()
        waitForRoute(AppRoutes.SETTINGS)
    }

    @Test fun unsavedEditorFollowsPredictiveBackAndKeepsDraftOnCancel() {
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        compose.runOnIdle { vm.changeDraft(vm.draft.copy(body = "取消后仍然保留的文字")) }
        compose.waitForIdle()
        val atRest = capture()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(412f, 400f, 0f, 1))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(220f, 400f, .5f, 1))
        }
        compose.waitForIdle()
        assertEquals(AppRoutes.EDITOR, nav.currentDestination?.route)
        assertTrue("Unsaved editor must animate with the system gesture", pixelDifference(atRest, capture()) > 0.02f)
        compose.onNodeWithText("取消后仍然保留的文字").assertExists()
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(AppRoutes.EDITOR, nav.currentDestination?.route)
        compose.onNodeWithText("取消后仍然保留的文字").assertExists()
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(412f, 400f, 0f, 1))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(120f, 400f, .88f, 1))
            back.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(AppRoutes.EDITOR, nav.currentDestination?.route)
        compose.onNodeWithText("放弃本次编辑？").assertExists()
        compose.onNodeWithText("放弃编辑").performClick()
        waitForRoute(AppRoutes.HOME)
    }

    @Test fun officialPredictiveBackOnBothEdgesRestoresOrCommitsTheCorrectDestination() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking {
            com.collection.data.LibraryStore(app).saveEntry(com.collection.domain.Entry(id = "direction", title = "方向测试"))
        }
        compose.runOnIdle { vm.reload() }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.loading }
        for ((route, tag) in listOf(AppRoutes.SETTINGS to "settings", AppRoutes.EDITOR to "editor", AppRoutes.DETAIL to "detail")) {
            compose.runOnIdle {
                when (route) {
                    AppRoutes.SETTINGS -> vm.openPage(Page.SETTINGS)
                    AppRoutes.EDITOR -> vm.newEntry()
                    else -> vm.openEntry(vm.library.entries.single())
                }
            }
            waitForRoute(route)
            for (edge in listOf(0, 1)) {
                compose.runOnIdle { back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, edge)) }
                compose.runOnIdle { back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(160f, 400f, .5f, edge)) }
                compose.waitForIdle()
                if (tag == "settings") screenshot("predictive-$tag-edge-$edge")
                compose.runOnIdle { back.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertEquals(route, nav.currentDestination?.route)
                compose.onNodeWithTag("page_$tag", useUnmergedTree = true).assertIsDisplayed()
                val restored = compose.onNodeWithTag("page_$tag", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
                assertEquals(0f, restored, 1f)
            }
            compose.runOnIdle {
                back.dispatchOnBackStarted(androidx.activity.BackEventCompat(412f, 400f, 0f, 1))
                back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(100f, 400f, .85f, 1))
                back.onBackPressed()
            }
            waitForRoute(AppRoutes.HOME)
        }
    }

    @Test fun predictiveSearchBackClosesOnlySearchAndSupportsCancellation() {
        compose.onNodeWithContentDescription("搜索资料").performClick()
        compose.onNodeWithText("搜索标题或标签").performTextInput("测试")
        compose.runOnIdle {
            back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0))
            back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(150f, 400f, .5f, 0))
            back.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertEquals("测试", vm.query)
        compose.onNodeWithContentDescription("关闭搜索").assertExists()
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        assertEquals("", vm.query)
        assertEquals(AppRoutes.HOME, nav.currentDestination?.route)
        compose.onNodeWithContentDescription("搜索资料").assertExists()
    }

    @Test fun syncSettingsUsesTheExistingPageStackAndReturnsToSettings() {
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("同步与备份").performScrollTo().performClick()
        waitForRoute(AppRoutes.SYNC)
        compose.onNodeWithText("同步与备份").assertIsDisplayed()
        compose.onNodeWithText("WebDAV 连接").assertIsDisplayed()
        screenshot("nav-sync")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("从备份恢复"))
        compose.onNodeWithText("从备份恢复").performClick()
        compose.onNodeWithText("合并导入备份").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.HOME)
    }

    @Test fun trashRestoresAndPermanentlyClearsOnlyTheSelectedEntries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val restore = com.collection.domain.Entry(title = "要恢复的条目")
        val purge = com.collection.domain.Entry(title = "要清除的条目")
        kotlinx.coroutines.runBlocking {
            val store = com.collection.data.LibraryStore(app)
            store.saveEntry(restore)
            store.saveEntry(purge)
        }
        compose.runOnIdle { vm.deleteEntries(setOf(restore.id, purge.id)) }
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.onNodeWithText("回收站").performScrollTo().performClick()
        waitForRoute(AppRoutes.TRASH)
        compose.onNodeWithContentDescription("恢复").assertDoesNotExist()
        compose.onNodeWithText(restore.title).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("恢复").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.library.entries.any { it.id == restore.id } || vm.operationError != null
        }
        assertNull(vm.operationError)
        assertEquals(restore.id, vm.library.entries.single().id)
        compose.onNodeWithText(purge.title).performClick()
        compose.onNodeWithContentDescription("永久清除").performClick()
        compose.onNodeWithText("永久清除 1 个条目？").assertIsDisplayed()
        compose.onNode(hasText("永久清除") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.uiState.value.trash.isEmpty() || vm.operationError != null
        }
        assertNull(vm.operationError)
        compose.onNodeWithText("回收站为空").assertIsDisplayed()
        assertEquals(restore.id, vm.library.entries.single().id)
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.SETTINGS)
    }

    @Test fun outgoingEditorKeepsItsContentWhileTheBackAnimationRuns() {
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        compose.runOnIdle { vm.changeDraft(vm.draft.copy(body = "返回期间保留的原文")) }
        compose.onNodeWithText("返回期间保留的原文", useUnmergedTree = true).assertExists()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { vm.cancelEditor() }
            waitForRoute(AppRoutes.HOME)
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.onNodeWithText("返回期间保留的原文", useUnmergedTree = true).assertExists()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithText("返回期间保留的原文").assertDoesNotExist()
    }

    @Test fun returningFromDetailRestoresHomeScrollPosition() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking {
            val store = com.collection.data.LibraryStore(app)
            repeat(30) { store.saveEntry(com.collection.domain.Entry(id = "item-$it", title = "条目%02d".format(it))) }
        }
        compose.runOnIdle { vm.updateHome(com.collection.domain.HomePreferences(sort = com.collection.domain.SortOrder.TITLE_ASC)); vm.reload() }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !vm.loading }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasTestTag("entry_item-14"))
        compose.onNodeWithTag("entry_item-14").assertIsDisplayed().performClick()
        waitForRoute(AppRoutes.DETAIL)
        compose.onNodeWithContentDescription("返回").performClick()
        waitForRoute(AppRoutes.HOME)
        compose.onNodeWithTag("entry_item-14").assertIsDisplayed()
    }

        /**
     * Miuix predictive back, frame by frame. The predictive and pop specs share one easing
     * curve, so the page must track the gesture through that curve and the release must
     * continue smoothly: no snap-back, no jump, and the final frame is exactly home.
     */
    @Test fun miuixPredictiveBackGestureTracksAndCommitsSmoothly() {
        waitForRoute(AppRoutes.HOME)
        compose.waitForIdle()
        val homeRest = Frame(capture())
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.waitForIdle()
        val settingsRest = Frame(capture())
        val width = settingsRest.width

        compose.runOnIdle { back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0)) }
        compose.waitForIdle()
        var lastShift = 0
        for (p in listOf(.1f, .2f, .3f)) {
            compose.runOnIdle { back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(p * 400f, 400f, p, 0)) }
            compose.waitForIdle()
            val shift = correlateShift(Frame(capture()), settingsRest)
                ?: error("page not measurable at progress $p")
            val expected = p * width
            assertEquals("gesture at progress $p must follow finger progress", expected, shift.toFloat(), 14f)
            assertTrue("gesture must move forward", shift >= lastShift)
            lastShift = shift
        }

        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.autoAdvance = false
        val frames = mutableListOf<Frame>()
        try {
            for (i in 1..80) {
                compose.mainClock.advanceTimeBy(16)
                compose.waitForIdle()
                frames += Frame(capture())
            }
        } finally { compose.mainClock.autoAdvance = true }

        // No snap-back: the first measurable frame must still be near the release point.
        val measurable = frames.mapIndexed { i, f -> i to correlateShift(f, settingsRest) }.filter { it.second != null }
        assertTrue("commit must keep the page measurable", measurable.isNotEmpty())
        assertTrue("commit must not snap back to rest", (measurable.first().second ?: 0) >= lastShift - 20)
        measurable.forEach { pair ->
            assertTrue("commit frame ${pair.first * 16}ms must not jump back", (pair.second ?: 0) >= lastShift - 20)
        }
        // Successive measurable shifts must never move backwards (no flicker).
        measurable.zipWithNext().forEach { pair ->
            assertTrue("commit must be monotonic", (pair.second?.second ?: 0) >= (pair.first.second ?: 0) - 4)
        }
        // The settled screen is exactly home.
        compose.waitForIdle()
        assertEquals(0f, meanDiff(Frame(capture()), homeRest), 1.5f)
        assertEquals(AppRoutes.HOME, nav.currentDestination?.route)
    }

    @Test fun miuixPredictiveBackCancelReturnsToRestSmoothly() {
        waitForRoute(AppRoutes.HOME)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("打开设置").performClick()
        waitForRoute(AppRoutes.SETTINGS)
        compose.waitForIdle()
        val settingsRest = Frame(capture())
        val width = settingsRest.width

        compose.runOnIdle { back.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, 0)) }
        compose.waitForIdle()
        compose.runOnIdle { back.dispatchOnBackProgressed(androidx.activity.BackEventCompat(120f, 400f, .3f, 0)) }
        compose.waitForIdle()
        val held = correlateShift(Frame(capture()), settingsRest) ?: error("page not measurable")
        assertEquals(.3f * width, held.toFloat(), 14f)

        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.mainClock.autoAdvance = false
        val frames = mutableListOf<Frame>()
        try {
            for (i in 1..80) {
                compose.mainClock.advanceTimeBy(16)
                compose.waitForIdle()
                frames += Frame(capture())
            }
        } finally { compose.mainClock.autoAdvance = true }

        val measurable = frames.map { correlateShift(it, settingsRest) }.filterNotNull()
        assertTrue("cancel must keep the page measurable", measurable.isNotEmpty())
        measurable.zipWithNext().forEach { pair ->
            assertTrue("cancel must not jump forward (got ${pair.first} then ${pair.second})", pair.second <= pair.first + 4)
        }
        compose.waitForIdle()
        assertEquals(0f, meanDiff(Frame(capture()), settingsRest), 1.5f)
        assertEquals(AppRoutes.SETTINGS, nav.currentDestination?.route)
    }

    /** The Miuix programmatic easing, replicated for expectations. */
    private fun miuixEase(fraction: Float): Float {
        val response = 0.8
        val damping = 0.95
        val angular = 2.0 * PI / response
        val k = angular * angular
        val c = damping * 4.0 * PI / response
        val w = sqrt(4.0 * k - c * c) / 2.0
        val r = -c / 2.0
        val c2 = r / w
        val t = fraction.toDouble()
        return (exp(r * t) * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }

    private fun meanDiff(a: Frame, b: Frame): Float {
        if (a.width != b.width || a.height != b.height) return 999f
        var diff = 0L
        for (i in a.pixels.indices step 7) diff += a.absDiffAt(i, b)
        return (diff / (a.pixels.size / 7)).toFloat()
    }

    /** Best horizontal shift s so that frame[x,y] ~= rest[x-s,y] for x >= s; null when unclear. */
    private fun correlateShift(frame: Frame, rest: Frame): Int? {
        var bestErr = Double.MAX_VALUE
        var bestShift = -1
        for (s in 0 until frame.width - 80) {
            var err = 0L
            var count = 0
            var y = 0
            while (y < frame.height) {
                var x = s
                while (x < frame.width) {
                    err += frame.absDiff(x, y, rest, x - s, y)
                    count++
                    x += 4
                }
                y += 4
            }
            if (count > 0 && err.toDouble() / count < bestErr) { bestErr = err.toDouble() / count; bestShift = s }
        }
        return if (bestShift >= 0 && bestErr < 12.0) bestShift else null
    }
private class Frame(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

        fun absDiffAt(index: Int, other: Frame): Long = absDiff(index % width, index / width, other, index % other.width, index / other.width)

        fun absDiff(x: Int, y: Int, other: Frame, otherX: Int, otherY: Int): Long {
            if (otherX < 0 || otherX >= other.width || otherY < 0 || otherY >= other.height) return 0
            val a = pixels[y * width + x]
            val b = other.pixels[otherY * other.width + otherX]
            return (android.graphics.Color.red(a) - android.graphics.Color.red(b)).let { r ->
                r.toLong() * r + (android.graphics.Color.green(a) - android.graphics.Color.green(b)).let { g -> g.toLong() * g } +
                    (android.graphics.Color.blue(a) - android.graphics.Color.blue(b)).let { bl -> bl.toLong() * bl }
            }
        }

        fun savePng(dir: java.io.File, name: String) {
            dir.resolve("$name.png").outputStream().use { out ->
                android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        private fun green(x: Int, y: Int): Boolean {
            val c = pixels[y * width + x]
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            return g > r + 20 && g >= b - 10 && g in 30..180 && r < 120
        }

        /** Widest horizontal run of primary-green pixels; returns its x range. */
        fun wideGreenRun(): IntRange? {
            var best: IntRange? = null
            var bestLen = 0
            for (y in 0 until height step 2) {
                var start = -1
                var x = 0
                while (x <= width) {
                    val hit = x < width && green(x, y)
                    if (hit) { if (start < 0) start = x } else if (start >= 0) {
                        val len = x - start
                        if (len > bestLen) { bestLen = len; best = start until x }
                        start = -1
                    }
                    x++
                }
            }
            return best
        }

        /** Leftmost start of a green run (>= 40px) anywhere in the given x band. */
        fun leftGreenRun(band: IntRange): Int? {
            for (y in 0 until height) {
                var runStart = -1
                var runLen = 0
                var x = band.first
                while (x < width) {
                    if (green(x, y)) {
                        if (runStart < 0) runStart = x
                        runLen++
                        if (runLen >= 40) return runStart
                    } else { runStart = -1; runLen = 0 }
                    x++
                    if (x > band.last + width) break
                }
            }
            return null
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun pixelDifference(first: Bitmap, second: Bitmap): Float {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val a = IntArray(first.width * first.height)
        val b = IntArray(second.width * second.height)
        first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
        var changed = 0
        for (index in a.indices) if (a[index] != b[index]) changed++
        return changed.toFloat() / a.size
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = capture()
        val path = java.io.File("build/reports/screenshots/$name.png").apply { parentFile?.mkdirs() }
        path.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp-mdpi")
    fun expandedWindowConstrainsTheFormInsteadOfStretchingTextAcrossTheScreen() {
        compose.onNodeWithText("开始第一条收录").performScrollTo().performClick()
        waitForRoute(AppRoutes.EDITOR)
        val notes = compose.onNode(hasSetTextAction() and hasText("文字与笔记")).fetchSemanticsNode().boundsInRoot
        assertTrue(notes.width <= 840f)
        assertTrue(notes.left > 100f)
        screenshot("nav-editor-wide")
    }
}









