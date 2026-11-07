package com.collection

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.collection.domain.Attachment
import com.collection.domain.Entry
import com.collection.domain.Tag
import com.collection.ui.*
import java.io.File
import org.junit.Assert.*
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
class MotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun revealingContentHasAnIntermediateHeightAndCannotBeClickedWhileLeaving() {
        var visible by mutableStateOf(false)
        var clicks = 0
        compose.setContent { CollTheme { Box(Modifier.fillMaxSize()) {
            MotionReveal(visible, Modifier.testTag("reveal")) {
                Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().height(120.dp)) { Text("移除资料") }
            }
        } } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val middle = compose.onNodeWithTag("reveal").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expansion must be visible before it reaches the final height", middle > 0f && middle < 120f)
        compose.runOnIdle { visible = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("移除资料").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(40f, 40f)) }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("reveal").assertDoesNotExist()
    }

    @Test fun interruptedContentSwapsExposeOnlyTheCurrentAction() {
        var next by mutableStateOf(false)
        var oldClicks = 0
        var newClicks = 0
        compose.setContent { CollTheme { MotionSwap(next) { value ->
            Button(onClick = { if (value) newClicks++ else oldClicks++ }) { Text(if (value) "新操作" else "旧操作") }
        } } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { next = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("旧操作").assertDoesNotExist()
        compose.onNodeWithText("新操作").performClick()
        compose.runOnIdle { next = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { next = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(2000)
        compose.onAllNodesWithText("新操作").assertCountEquals(1)
        compose.onNodeWithText("旧操作").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, oldClicks); assertEquals(1, newClicks) }
    }

    @Test fun selectingATagMovesItSmoothlyAndKeepsItsIdentity() {
        val first = Tag("first", "第一标签")
        val second = Tag("second", "第二标签")
        var entry by mutableStateOf(Entry(title = "标签位移动画"))
        compose.setContent { CollTheme { DetailScreen(entry, listOf(first, second), {}, {}, {}, onUpdate = { entry = it }) } }
        val destination = compose.onNodeWithTag("entry_tag_first").fetchSemanticsNode().boundsInRoot.left
        val start = compose.onNodeWithTag("entry_tag_second").fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("entry_tag_second").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val middle = compose.onNodeWithTag("entry_tag_second").fetchSemanticsNode().boundsInRoot.left
        assertTrue("The selected tag must travel rather than jump", middle > destination && middle < start)
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("entry_tag_second").assertIsSelected()
        assertEquals(destination, compose.onNodeWithTag("entry_tag_second").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.runOnIdle { assertEquals(setOf(second.id), entry.tagIds) }
    }

    @Test fun paletteChangesPassThroughAnIntermediateColorAndReachTheExactTarget() {
        var blue by mutableStateOf(false)
        compose.setContent {
            val colors = animatedColorScheme(lightColorScheme(primary = if (blue) Color.Blue else Color.Red))
            Box(Modifier.size(80.dp).background(colors.primary).testTag("color_sample"))
        }
        fun pixel() = compose.onNodeWithTag("color_sample").captureToImage().asAndroidBitmap().getPixel(40, 40)
        assertEquals(android.graphics.Color.RED, pixel())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { blue = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val middle = pixel()
        assertNotEquals(android.graphics.Color.RED, middle)
        assertNotEquals(android.graphics.Color.BLUE, middle)
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(android.graphics.Color.BLUE, pixel())
    }

    @Test fun previewErrorCanRetryIntoAnImageWithoutLosingItsCloseAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "preview-retry-${System.nanoTime()}.png")
        val attachment = Attachment(id = "retry", name = file.name, path = file.path, mime = "image/png", size = 0)
        var closed = 0
        compose.setContent { CollTheme { AttachmentPreview(attachment, embedded = true) { closed++ } } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("原始文件不存在", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.onNodeWithText("重试", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(file.name).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertTrue(file.length() > 0) }
    }
}
