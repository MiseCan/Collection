package com.collection.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.collection.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OriginalVisual(attachment: Attachment? = null, icon: EntryIcon = EntryIcon.TEXT, size: Dp = 56.dp, webUrl: String? = null) {
    val kind = attachment?.inferredIcon() ?: icon
    val bitmap by produceState<Bitmap?>(null, attachment?.id, attachment?.path) {
        value = if (attachment != null && kind == EntryIcon.IMAGE) withContext(Dispatchers.IO) {
            runCatching { loadOriginalThumbnail(attachment.path) }.getOrNull()
        } else null
    }
    val website = attachment == null && kind == EntryIcon.LINK && webUrl != null
    val siteIcon by rememberSiteIcon(if (website) webUrl else null)
    Surface(modifier = Modifier.size(size), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer) {
        MotionSwap(if (website) siteIcon else bitmap, Modifier.fillMaxSize()) { image ->
            if (image != null) Image(image.asImageBitmap(), if (website) "网站图标" else "图片缩略图",
                Modifier.fillMaxSize().then(if (website) Modifier.padding(size * .15f) else Modifier),
                contentScale = if (website) ContentScale.Fit else ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EntryTypeIcon(kind, Modifier.size(size * .57f)) }
        }
    }
}

@Composable
internal fun EntryOriginalStack(entry: Entry) {
    val originals = remember(entry.body, entry.attachments) {
        (if (entry.body.isNotBlank()) listOf<Attachment?>(null) else emptyList()) + entry.attachments
    }.take(3)
    val firstLink = remember(entry.body) { extractWebLinks(entry.body).firstOrNull()?.url }
    val textIcon = if (firstLink != null) EntryIcon.LINK else EntryIcon.TEXT
    Box(Modifier.size(56.dp).testTag("stack_container_${entry.id}"), contentAlignment = Alignment.Center) {
        if (originals.isEmpty()) OriginalVisual(icon = EntryIcon.COLLECTION, size = 40.dp)
        else Box(Modifier.motionContentSize().size((40 + (originals.size - 1) * 4).dp)) {
            originals.forEachIndexed { index, attachment -> key(attachment?.id ?: "__body__") {
                MotionPlacement(Modifier.offset((index * 4).dp, (index * 4).dp)
                    .zIndex((originals.size - index).toFloat()).testTag("stack_${entry.id}_$index")) {
                    OriginalVisual(attachment, textIcon, size = 40.dp, webUrl = if (attachment == null) firstLink else null)
                }
            } }
        }
    }
}
