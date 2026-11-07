@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round

/** Exiting content is painted until settled, but cannot be clicked or announced twice. */
private fun Modifier.motionActive(active: Boolean): Modifier = if (active) this else
    clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    }

@Composable
internal fun MotionReveal(visible: Boolean, modifier: Modifier = Modifier, horizontal: Boolean = false,
    content: @Composable () -> Unit) {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(visible, modifier,
        enter = fadeIn(effects) + if (horizontal) expandHorizontally(spatial, expandFrom = Alignment.Start) else expandVertically(spatial, expandFrom = Alignment.Top),
        exit = fadeOut(effects) + if (horizontal) shrinkHorizontally(spatial, shrinkTowards = Alignment.Start) else shrinkVertically(spatial, shrinkTowards = Alignment.Top)) {
        Box(Modifier.motionActive(visible)) { content() }
    }
}

@Composable
internal fun <T> MotionSwap(state: T, modifier: Modifier = Modifier, contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit) {
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
    AnimatedContent(state, modifier, transitionSpec = {
        (fadeIn(effects) togetherWith fadeOut(effects)).using(SizeTransform(clip = true) { _, _ -> spatial })
    }, contentKey = contentKey, label = "content_change") { value ->
        Box(Modifier.motionActive(contentKey(value) == contentKey(state))) { content(value) }
    }
}

@Composable
internal fun Modifier.motionContentSize(): Modifier = animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())

/** Reuse the clickable's native interaction source; never install a second tap detector. */
@Composable
internal fun Modifier.motionPressFeedback(interactions: InteractionSource): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "content_press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** FlowRow has no LazyItemScope.animateItem. Animate its visual placement with public APIs. */
@Composable
internal fun MotionPlacement(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var initialized by remember { mutableStateOf(false) }
    val position = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    LaunchedEffect(target, enabled) {
        val next = target ?: return@LaunchedEffect
        if (!initialized || !enabled) { position.snapTo(next); initialized = true }
        else position.animateTo(next, spatial)
    }
    Box(modifier.onPlaced { target = it.positionInParent().round() }) {
        Box(Modifier.graphicsLayer {
            if (enabled && initialized) {
                val delta = position.value - (target ?: position.value)
                translationX = delta.x.toFloat(); translationY = delta.y.toFloat()
            }
        }) { content() }
    }
}
