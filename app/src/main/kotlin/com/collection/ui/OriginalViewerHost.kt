package com.collection.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The application embeds viewers in the shared page stack; standalone callers retain a dialog. */
@Composable
internal fun OriginalViewerHost(embedded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content()
    else Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        content = content)
}
