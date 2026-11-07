@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.collection.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.collection.domain.EntryIcon
import com.collection.domain.inferredIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.collection.domain.Attachment
import java.io.File

internal fun Attachment.hasNativeViewer(): Boolean = mime.startsWith("image/") || mime.startsWith("text/") ||
    name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "txt", "md")

internal fun openOriginalExternally(context: Context, attachment: Attachment): String? = try {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(attachment.path), attachment.name)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime.ifBlank { "application/octet-stream" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(attachment.name, uri) }
    context.startActivity(Intent.createChooser(intent, "打开 ${attachment.name}"))
    null
} catch (_: Exception) { "没有可打开此文件的应用，或原件暂时不可读，请安装相应阅读器后重试。" }

@Composable
fun OriginalFileCard(attachment: Attachment, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Card(modifier = Modifier.fillMaxWidth().motionContentSize().heightIn(min = 80.dp).motionPressFeedback(interactions)
        .combinedClickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OriginalVisual(attachment = attachment)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MotionSwap(attachment.title.ifBlank { attachment.name }) { title ->
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }
                Text(formatSize(attachment.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OriginalTypeBadge(icon: EntryIcon) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { EntryTypeIcon(icon, Modifier.size(32.dp)) }
    }
}

@Composable
fun OriginalTextCard(title: String = "", hasLinks: Boolean = false, onLongClick: (() -> Unit)? = null, webUrl: String? = null, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Card(modifier = Modifier.fillMaxWidth().motionContentSize().heightIn(min = 80.dp).testTag("original_text_card")
        .motionPressFeedback(interactions).combinedClickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OriginalVisual(icon = if (hasLinks) EntryIcon.LINK else EntryIcon.TEXT, webUrl = webUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MotionSwap(title.ifBlank { "文字内容" }) { text ->
                    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

internal fun loadOriginalThumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
}

@Composable
fun OriginalTextViewer(text: String, title: String = "文字与笔记", embedded: Boolean = false,
    onSaveText: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(text) }
    OriginalViewerHost(embedded, onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭文字查看") }
            }, actions = {
                if (onSaveText != null) IconButton(enabled = value != text, onClick = { onSaveText(value) }) {
                    Icon(Icons.Default.Check, "保存文字")
                }
            }) }) { padding ->
                if (onSaveText != null) OutlinedTextField(value = value, onValueChange = { value = it },
                    modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).testTag("original_text_editor"))
                else
                SelectionContainer {
                    Text(text, Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp).testTag("original_full_text"),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}


