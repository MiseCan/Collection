@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * State shared by [PredictiveBackHandler] and a screen's graphics layer.
 *
 * The progress is driven directly by the system's [BackEventCompat] stream. It is not a
 * post-gesture exit animation: every move event seeks the visual state immediately, and a
 * cancelled gesture animates the same state back to rest.
 */
@Stable
internal class PredictiveBackMotion {
    internal val progress = Animatable(0f)

    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set

    /** Positive moves the page towards the right, matching a left-edge swipe. */
    val direction: Float
        get() = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f

    val value: Float
        get() = progress.value.coerceIn(0f, 1f)
}

/**
 * Registers a local predictive-back handler for a transient surface (search, unsaved form,
 * bottom sheet, and so on). Normal destination pops stay with Navigation Compose.
 *
 * Completion is reported only after the system completes the gesture. Cancellation restores the
 * visual state with a short spring, while completion settles back to rest before the caller
 * performs its real action.
 */
@Composable
internal fun rememberPredictiveBackMotion(
    enabled: Boolean,
    onComplete: () -> Unit,
): PredictiveBackMotion {
    val scope = rememberCoroutineScope()
    val motion = remember { PredictiveBackMotion() }
    val complete by rememberUpdatedState(onComplete)
    val settle = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            motion.progress.snapTo(0f)
            events.collect { event ->
                motion.edge = event.swipeEdge
                motion.progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            complete()
        } finally {
            // The gesture coroutine is cancelled by PredictiveBackHandler when the user cancels,
            // so restoration must run in the composition scope rather than on the cancelled job.
            scope.launch {
                motion.progress.animateTo(0f, animationSpec = settle)
            }
        }
    }
    return motion
}

/** Draws the live gesture transform without consuming touches or simulating edge gestures. */
internal fun Modifier.predictiveBackTransform(
    motion: PredictiveBackMotion,
    maxTranslation: Dp = 28.dp,
    maxScale: Float = 0.035f,
    maxAlpha: Float = 0.12f,
): Modifier = graphicsLayer {
    val progress = motion.value
    translationX = motion.direction * maxTranslation.toPx() * progress
    val scale = 1f - maxScale * progress
    scaleX = scale
    scaleY = scale
    alpha = 1f - maxAlpha * progress
}
