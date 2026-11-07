package com.collection.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keep Material's chip touch/scroll/ripple behavior; long press uses its native press stream. */
@Composable
fun TagFilterChip(label: String, selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, longPressLabel: String = "标签操作") {
    val interactions = remember { MutableInteractionSource() }
    var handledLongPress by remember { mutableStateOf(false) }
    val currentLongPress by rememberUpdatedState(onLongPress)
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    LaunchedEffect(interactions, timeout, enabled) {
        if (!enabled) return@LaunchedEffect
        var pending: Job? = null
        try {
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        handledLongPress = false; pending?.cancel()
                        pending = launch { delay(timeout); handledLongPress = true; currentLongPress() }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> pending?.cancel()
                }
            }
        } finally { pending?.cancel() }
    }
    FilterChip(selected = selected, enabled = enabled, onClick = {
        if (!handledLongPress) onClick()
        handledLongPress = false
    }, label = { Text(label, maxLines = 1) }, interactionSource = interactions,
        modifier = modifier.semantics { if (enabled) onLongClick(longPressLabel) { currentLongPress(); true } })
}
