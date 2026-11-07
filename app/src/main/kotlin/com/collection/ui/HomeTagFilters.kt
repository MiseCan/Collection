@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.collection.domain.Tag
import kotlin.math.abs

/** Native long-press drag, across FlowRow lines; only a completed drop is persisted. */
@Composable
internal fun HomeTagFilters(tags: List<Tag>, selected: Set<String>, onSelect: (Set<String>) -> Unit,
    onReorder: (List<String>) -> Unit, viewport: Rect, onAutoScroll: suspend (Float) -> Float) {
    var order by remember { mutableStateOf(tags) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pointerRoot by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val currentTags by rememberUpdatedState(tags)
    val commit by rememberUpdatedState(onReorder)
    val scroll by rememberUpdatedState(onAutoScroll)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(tags) { if (dragged == null) order = tags }
    fun moveAt(position: Offset) {
        val id = dragged ?: return
        val target = order.firstOrNull { it.id != id && bounds[it.id]?.contains(position) == true } ?: return
        val from = order.indexOfFirst { it.id == id }
        val to = order.indexOfFirst { it.id == target.id }
        if (from >= 0 && to >= 0) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    // The active pointer stays in root coordinates while the grid scrolls underneath it.
    val edge = 56.dp
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { edge.toPx() }
    val speedPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    val scrollSpeed = if (dragged == null || viewport.height <= 0) 0f else when {
        pointerRoot.y < viewport.top + edgePx -> -speedPx * ((viewport.top + edgePx - pointerRoot.y) / edgePx).coerceIn(0f, 1f)
        pointerRoot.y > viewport.bottom - edgePx -> speedPx * ((pointerRoot.y - viewport.bottom + edgePx) / edgePx).coerceIn(0f, 1f)
        else -> 0f
    }
    LaunchedEffect(dragged, scrollSpeed) {
        if (scrollSpeed == 0f) return@LaunchedEffect
        while (dragged != null) {
            withFrameNanos { }
            if (abs(scroll(scrollSpeed)) < .5f) break
            moveAt(pointerRoot - origin)
        }
    }
    FlowRow(Modifier.fillMaxWidth().motionContentSize().testTag("home_tag_filters")
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val tag = order.firstOrNull { bounds[it.id]?.contains(position) == true }
                    if (tag != null) {
                        dragged = tag.id; pointerRoot = origin + position
                        grabOffset = position - bounds.getValue(tag.id).topLeft
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, _ ->
                    if (dragged != null) { pointerRoot = origin + change.position; moveAt(change.position) }
                },
                onDragEnd = {
                    val active = dragged != null
                    dragged = null
                    if (active && order.map { it.id } != currentTags.map { it.id }) commit(order.map { it.id })
                },
                onDragCancel = { dragged = null; order = currentTags },
            )
        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected.isEmpty(), onClick = { onSelect(emptySet()) }, label = { Text("全部") })
        order.forEachIndexed { index, tag -> key(tag.id) {
            val active = dragged == tag.id
            val scale by animateFloatAsState(if (active) 1.06f else 1f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "tag_drag_scale")
            MotionPlacement(Modifier.zIndex(if (active) 1f else 0f).onGloballyPositioned { bounds[tag.id] = it.boundsInParent() }, enabled = !active) {
                FilterChip(selected = tag.id in selected,
                    onClick = { if (dragged == null) onSelect(if (tag.id in selected) selected - tag.id else selected + tag.id) },
                    label = { Text(tag.name, maxLines = 1) },
                    modifier = Modifier.testTag("home_tag_${tag.id}").graphicsLayer {
                        val slot = bounds[tag.id]
                        if (active && slot != null) {
                            val translation = pointerRoot - origin - grabOffset - slot.topLeft
                            translationX = translation.x; translationY = translation.y
                        }
                        scaleX = scale; scaleY = scale
                    }.semantics {
                        // Accessibility users can reorder without a touch drag or a visual menu.
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("向前移动") {
                                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                commit(order.map { it.id }); true
                            } else null,
                            if (index < order.lastIndex) CustomAccessibilityAction("向后移动") {
                                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                commit(order.map { it.id }); true
                            } else null)
                    })
            }
        } }
    }
}
