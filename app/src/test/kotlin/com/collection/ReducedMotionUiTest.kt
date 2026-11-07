package com.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.collection.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReducedMotionUiTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    @Test fun zeroDurationScaleSettlesExpansionAndPaletteWithoutWaitingForASpring() {
        var shown by mutableStateOf(false)
        compose.setContent { CollTheme {
            val colors = animatedColorScheme(lightColorScheme(primary = if (shown) Color.Blue else Color.Red))
            Column {
                Box(Modifier.size(80.dp).background(colors.primary).testTag("color"))
                MotionReveal(shown, Modifier.testTag("reveal")) { Box(Modifier.size(120.dp)) }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = true; Snapshot.sendApplyNotifications() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(120f, compose.onNodeWithTag("reveal").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(android.graphics.Color.BLUE, compose.onNodeWithTag("color").captureToImage().asAndroidBitmap().getPixel(40, 40))
    }
}
