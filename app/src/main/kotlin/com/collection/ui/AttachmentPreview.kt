@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.collection.domain.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class PreviewData(val bitmap: Bitmap? = null, val text: String? = null, val pageCount: Int = 0, val error: String? = null, val pageIndex: Int = 0)

@Composable
fun AttachmentPreview(attachment: Attachment, embedded: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by remember(attachment.id) { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var openError by remember { mutableStateOf<String?>(null) }
    var scale by remember(page, attachment.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(page, attachment.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(page, attachment.id) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { centroid, zoom, pan, _ ->
        val previousScale = scale
        scale = (scale * zoom).coerceIn(1f, 5f)
        val ratio = scale / previousScale
        val focusX = if (centroid.isSpecified) centroid.x - viewport.width / 2f else 0f
        val focusY = if (centroid.isSpecified) centroid.y - viewport.height / 2f else 0f
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        offsetX = (focusX - (focusX - offsetX) * ratio + pan.x).coerceIn(-maxX, maxX)
        offsetY = (focusY - (focusY - offsetY) * ratio + pan.y).coerceIn(-maxY, maxY)
    }
    val pdf = attachment.name.endsWith(".pdf", true) || attachment.mime == "application/pdf"
    val image = attachment.mime.startsWith("image/") || attachment.name.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif")
    val plain = attachment.mime.startsWith("text/") || attachment.name.substringAfterLast('.').lowercase() in listOf("txt", "md")
    val data by produceState<PreviewData?>(null, attachment.id, page, retry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(attachment.path)
                require(file.isFile) { "原始文件不存在，可从备份恢复资料" }
                when {
                    pdf -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            require(renderer.pageCount > 0) { "PDF 没有可显示的页面" }
                            renderer.openPage(page.coerceAtMost(renderer.pageCount - 1)).use { p ->
                                val ratio = 1600.0 / maxOf(p.width, p.height).coerceAtLeast(1)
                                val width = (p.width * ratio).toInt().coerceAtLeast(1)
                                val height = (p.height * ratio).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                PreviewData(bitmap = bitmap, pageCount = renderer.pageCount)
                            }
                        }
                    }
                    image -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式" }
                        var sample = 1
                        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        requireNotNull(bitmap) { "图片读取失败" }
                        PreviewData(bitmap = bitmap)
                    }
                    plain -> {
                        file.inputStream().buffered(16384).use { input ->
                            input.mark(8194)
                            val probe = ByteArray(8192)
                            val count = input.read(probe).coerceAtLeast(0)
                            input.reset()
                            val encoding = when {
                                count > 1 && probe[0] == 0xff.toByte() && probe[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                                count > 1 && probe[0] == 0xfe.toByte() && probe[1] == 0xff.toByte() -> Charsets.UTF_16BE
                                else -> {
                                    val decoder = Charsets.UTF_8.newDecoder()
                                    val valid = !decoder.decode(java.nio.ByteBuffer.wrap(probe, 0, count), java.nio.CharBuffer.allocate(8192), false).isError
                                    if (valid) Charsets.UTF_8 else charset("GB18030")
                                }
                            }
                            input.reader(encoding).buffered().use { reader ->
                                val (text, hasNext) = readOriginalTextPage(reader, page)
                                PreviewData(text = text.removePrefix("\uFEFF"), pageCount = page + if (hasNext) 2 else 1)
                            }
                        }
                    }
                    else -> PreviewData(text = null)
                }
            } catch (e: Exception) { PreviewData(error = e.message ?: "预览失败") }
        }.copy(pageIndex = page)
    }
    var knownPageCount by remember(attachment.id) { mutableIntStateOf(0) }
    SideEffect { if ((data?.pageCount ?: 0) > 0) knownPageCount = data!!.pageCount }
    fun openExternal() {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(attachment.path), attachment.name)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime.ifBlank { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("原始资料", uri) }
            context.startActivity(Intent.createChooser(intent, "打开 ${attachment.name}"))
        } catch (_: Exception) { openError = "没有可打开此文件的应用，请安装相应的阅读器后重试。" }
    }
    OriginalViewerHost(embedded, onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(attachment.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭预览") } },
                actions = { IconButton(onClick = { openExternal() }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "其他应用打开") } }) },
                bottomBar = {
                    MotionReveal((pdf || plain) && knownPageCount > 0) { FlexibleBottomAppBar {
                        IconButton(onClick = { page-- }, enabled = page > 0 && data != null) { Icon(Icons.Default.ChevronLeft, "上一页") }
                        MotionSwap(page, Modifier.weight(1f)) { index -> Text(if (plain) "第 ${index + 1} 段" else "${index + 1} / $knownPageCount", textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        IconButton(onClick = { page++ }, enabled = data != null && page < knownPageCount - 1) { Icon(Icons.Default.ChevronRight, "下一页") }
                    } }
                }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).clipToBounds(), contentAlignment = Alignment.Center) {
                    MotionSwap(data, Modifier.fillMaxSize(), contentKey = { preview ->
                        preview?.let { it.pageIndex to when { it.error != null -> "error"; it.bitmap != null -> "image"; it.text != null -> "text"; else -> "file" } }
                    }) { preview ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        preview == null -> LoadingIndicator()
                        preview!!.error != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(preview!!.error!!); TextButton(onClick = { retry++ }) { Text("重试") }; OutlinedButton(onClick = { openExternal() }) { Text("使用其他应用打开") }
                        }
                        preview!!.bitmap != null -> Image(preview!!.bitmap!!.asImageBitmap(), attachment.name,
                            Modifier.fillMaxSize().onSizeChanged { viewport = it }
                                .transformable(state = transformState, canPan = { scale > 1f }).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY }, contentScale = ContentScale.Fit)
                        preview!!.text != null -> androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(preview!!.text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                        else -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Description, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("原始文件已完整保留", style = MaterialTheme.typography.titleLarge)
                            Text("${attachment.name}\n${formatSize(attachment.size)}", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Button(onClick = { openExternal() }) { Text("使用其他应用打开") }
                        }
                    }
                    }
                    }
                }
            }
            if (openError != null) AlertDialog(onDismissRequest = { openError = null }, title = { Text("暂时无法打开") }, text = { Text(openError!!) }, confirmButton = { TextButton(onClick = { openError = null }) { Text("知道了") } })
        }
    }
}

