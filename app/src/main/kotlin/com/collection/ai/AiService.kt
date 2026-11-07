package com.collection.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.collection.domain.AiConfig
import com.collection.domain.AiResult
import com.collection.domain.Attachment
import com.collection.web.WebPages
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Original files are read only. Any image conversions live in this request's disposable cache. */
class AiService internal constructor(private val context: Context, private val client: DeepSeekClient) {
    constructor(context: Context) : this(context, DeepSeekClient())

    suspend fun generate(
        body: String,
        attachments: List<Attachment>,
        config: AiConfig,
        onProgress: (String) -> Unit = {},
    ): AiResult = generateContent(body, attachments, config, true, onProgress)

    /** Generated topics are model output, not new URLs to crawl. */
    suspend fun generateTopics(body: String, config: AiConfig, onProgress: (String) -> Unit = {}): AiResult =
        generateContent(body, emptyList(), config, false, onProgress)

    private suspend fun generateContent(body: String, attachments: List<Attachment>, config: AiConfig,
        resolveWeb: Boolean, onProgress: (String) -> Unit): AiResult = withContext(Dispatchers.IO) {
        if (!config.proxyMode && config.apiKey.isBlank()) throw AiFailure("请先在设置中填写 DeepSeek API Key")
        val cache = File(context.cacheDir, "ai-${UUID.randomUUID()}").apply { mkdirs() }
        val warnings = mutableListOf<String>()
        val parts = mutableListOf<SourcePart>()
        try {
            if (body.isNotBlank()) addText(parts, "条目文字", body, warnings)
            PDFBoxResourceLoader.init(context.applicationContext)
            attachments.forEachIndexed { index, asset ->
                coroutineContext.ensureActive()
                onProgress("正在读取资料 ${index + 1}/${attachments.size}：${asset.name}")
                try {
                    val file = resolveOriginal(asset)
                    val extension = asset.name.substringAfterLast('.', "").lowercase()
                    when {
                        asset.mime == "application/pdf" || extension == "pdf" -> parsePdf(file, asset.name, parts, warnings)
                        extension == "docx" || asset.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                            parseDocx(file, asset.name, cache, parts, warnings)
                        extension == "doc" || asset.mime.equals("application/msword", ignoreCase = true) -> {
                            if (file.length() > MAX_LEGACY_WORD_BYTES) {
                                warnings += "${asset.name}：旧版 Word 超过 32 MiB，未读取正文"
                            } else {
                                addText(parts, asset.name, readLegacyWordBody(file), warnings)
                            }
                        }
                        asset.mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> {
                            parts += SourcePart.Image(asset.name, ImageSource.FileImage(file))
                            if (extension == "gif" || asset.mime == "image/gif") warnings += "${asset.name}：GIF 仅分析首帧，动画后续帧未读取"
                        }
                        asset.mime.startsWith("text/") || extension in setOf("txt", "md", "markdown") -> {
                            val bytes = file.inputStream().use { readLimited(it, MAX_TEXT_BYTES, asset.name, warnings) }
                            addText(parts, asset.name, decodeText(bytes), warnings)
                        }
                        else -> warnings += "${asset.name}：此格式暂不支持自动读取，原件已保留，未参与 AI 标题"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    warnings += "${asset.name}：解析失败或文件不可读，原件已保留，未读取部分未参与 AI 标题"
                }
            }
            val texts = parts.filterIsInstance<SourcePart.Text>().map { LinkedText(it.label, it.text) }
            val linked = if (resolveWeb) readLinkedSources(texts, read = { WebPages.client.readPage(it) }, onProgress = onProgress)
                else LinkedSources(texts, emptyList(), emptyList())
            warnings += linked.warnings
            if (linked.domains.isNotEmpty()) {
                parts.removeAll { it is SourcePart.Text }
                linked.texts.forEach { addText(parts, it.label, it.text, warnings) }
            }
            val batches = buildBatches(parts)
            if (batches.isEmpty()) throw AiFailure(
                if (warnings.isEmpty()) "请先添加文字或可读取的资料" else "没有成功读取到可供 AI 标题的资料。${warnings.joinToString("；")}".take(1500)
            )
            val mapped = mutableListOf<String>()
            var onlyResult: GeneratedText? = null
            batches.forEachIndexed { index, batch ->
                coroutineContext.ensureActive()
                onProgress("正在分析资料 ${index + 1}/${batches.size}")
                val images = mutableListOf<ImageInput>()
                for (part in batch.images) {
                    coroutineContext.ensureActive()
                    try {
                        val jpeg = when (val source = part.source) {
                            is ImageSource.FileImage -> normalizeImage(source.file)
                            is ImageSource.PdfPage -> renderPdfPage(source.file, source.index)
                        }
                        images += ImageInput(part.label, jpeg)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        warnings += "${part.label}：图片或页面无法读取，未参与 AI 标题"
                    }
                }
                if (batch.text.isBlank() && batch.images.isNotEmpty() && images.isEmpty()) return@forEachIndexed
                val result = client.summarize(batch.text, images, config, final = batches.size == 1, onRetry = onProgress)
                onlyResult = result
                mapped += result.title
            }
            var result = onlyResult ?: throw AiFailure("AI 没有可读取的资料。${warnings.joinToString("；")}".take(1500))
            if (mapped.size > 1) {
                var summaries = mapped
                var round = 1
                while (true) {
                    coroutineContext.ensureActive()
                    val chunks = chunkText(summaries.joinToString("\n\n"))
                    val next = mutableListOf<String>()
                    chunks.forEachIndexed { index, chunk ->
                        onProgress("正在综合全部资料 · 第 $round 轮 ${index + 1}/${chunks.size}")
                        result = client.summarize("综合各资料主题：\n$chunk", emptyList(), config, final = chunks.size == 1, onRetry = onProgress)
                        next += result.title
                    }
                    if (chunks.size == 1) break
                    if (round >= 8) throw AiFailure("资料量过大，汇总未能在安全轮数内完成，请拆分条目后重试")
                    summaries = next
                    round++
                }
            }
            AiResult(result.title, warnings.distinct(), linked.domains)
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun resolveOriginal(asset: Attachment): File {
        val root = File(context.filesDir, "originals").canonicalFile
        val file = (if (File(asset.path).isAbsolute) File(asset.path) else File(root, asset.path)).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && file.isFile) { "Invalid original file" }
        return file
    }

    private fun addText(parts: MutableList<SourcePart>, label: String, value: String, warnings: MutableList<String>) {
        val text = if (value.length > MAX_TEXT_CHARS) {
            warnings += "$label：文字超过 $MAX_TEXT_CHARS 字符安全上限，超出部分未读取"
            value.take(MAX_TEXT_CHARS)
        } else value
        if (text.isBlank()) {
            warnings += "$label：未提取到可读文字"
        } else {
            chunkText(text).forEachIndexed { index, chunk -> parts += SourcePart.Text("$label · 片段 ${index + 1}", chunk) }
        }
    }

    private suspend fun parsePdf(file: File, label: String, parts: MutableList<SourcePart>, warnings: MutableList<String>) {
        var document: PDDocument? = null
        try {
            document = runCatching {
                PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024).setTempDir(context.cacheDir))
            }.getOrNull()
            val totalPages = document?.numberOfPages ?: ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
            val pageCount = totalPages.coerceAtMost(MAX_PDF_PAGES)
            if (totalPages > pageCount) warnings += "$label：共 $totalPages 页，仅处理前 $pageCount 页，后续页未读取"
            var totalText = 0
            for (pageIndex in 0 until pageCount) {
                coroutineContext.ensureActive()
                val pageLabel = "$label · 第 ${pageIndex + 1} 页"
                var text = ""
                if (document != null) {
                    try {
                        text = PDFTextStripper().apply {
                            sortByPosition = true
                            startPage = pageIndex + 1
                            endPage = pageIndex + 1
                        }.getText(document)
                        if (totalText + text.length <= MAX_TEXT_CHARS) {
                            if (text.isNotBlank()) addText(parts, pageLabel, text, warnings)
                            totalText += text.length
                        } else {
                            warnings += "$pageLabel：超过单文件文字安全上限，此页文字未读取"
                        }
                    } catch (_: Exception) {
                        warnings += "$pageLabel：文字提取失败，改为页面图像识别"
                    }
                }
                val hasGraphics = document?.let { doc ->
                    runCatching {
                        val page = doc.getPage(pageIndex)
                        val resources = page.resources
                        // Form XObjects can contain nested images and charts as well.
                        val externalObjects = resources?.xObjectNames?.iterator()?.hasNext() == true
                        // Vector charts may have no XObject. Detect painted paths in a bounded
                        // decoded content prefix; if larger, conservatively inspect the page image.
                        val stream = page.contents
                        val bytes = stream.use { it.readBytesBounded(1_048_576) }
                        externalObjects || bytes.size >= 1_048_576 ||
                            Regex("(?:^|\\s)(?:S|s|f|F|f\\*|B|B\\*|b|b\\*)(?=\\s|$)")
                                .containsMatchIn(String(bytes, Charsets.ISO_8859_1))
                    }.getOrDefault(true)
                } ?: true
                if (text.trim().length < 40 || hasGraphics) {
                    parts += SourcePart.Image(pageLabel, ImageSource.PdfPage(file, pageIndex))
                }
            }
        } finally {
            document?.close()
        }
    }

    private suspend fun parseDocx(file: File, label: String, cache: File, parts: MutableList<SourcePart>, warnings: MutableList<String>) {
        ZipFile(file).use { zip ->
            val text = StringBuilder()
            val entries = zip.entries()
            var extractedBytes = 0L
            var imageCount = 0
            var entryCount = 0
            var foundMain = false
            while (entries.hasMoreElements()) {
                coroutineContext.ensureActive()
                val entry = entries.nextElement()
                entryCount++
                if (entryCount > 10_000) {
                    warnings += "$label：压缩包条目超过 10000 个，剩余部分未读取"
                    break
                }
                if (entry.isDirectory) continue
                val isWordXml = entry.name == "word/document.xml" ||
                    entry.name.matches(Regex("word/(header|footer|footnotes|endnotes|comments)[0-9]*\\.xml"))
                if (isWordXml) {
                    if (entry.name == "word/document.xml") foundMain = true
                    val bytes = zip.getInputStream(entry).use { readLimited(it, MAX_TEXT_BYTES, "$label（${entry.name}）", warnings) }
                    extractedBytes += bytes.size
                    if (extractedBytes > MAX_DOCX_BYTES) {
                        warnings += "$label：解压内容超过 100 MiB，剩余部分未读取"
                        break
                    }
                    try {
                        val parser = Xml.newPullParser().apply {
                            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                            // No DTD/entity expansion. DOCX text must never trigger external fetches.
                            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
                            setInput(bytes.inputStream(), null)
                        }
                        var inText = false
                        var event = parser.eventType
                        while (event != XmlPullParser.END_DOCUMENT) {
                            if (event == XmlPullParser.DOCDECL) throw IllegalArgumentException("DTD not allowed")
                            when (event) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "t" -> inText = true
                                    "tab" -> text.append('\t')
                                    "br" -> text.append('\n')
                                }
                                XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                                XmlPullParser.END_TAG -> when (parser.name) {
                                    "t" -> inText = false
                                    "p", "tr" -> text.append('\n')
                                    "tc" -> text.append('\t')
                                }
                            }
                            if (text.length > MAX_TEXT_CHARS) break
                            event = parser.nextToken()
                        }
                    } catch (_: Exception) {
                        warnings += "$label：${entry.name} 部分文字解析失败"
                    }
                } else if (entry.name.startsWith("word/media/")) {
                    imageCount++
                    if (imageCount > MAX_DOCX_IMAGES) {
                        if (imageCount == MAX_DOCX_IMAGES + 1) warnings += "$label：嵌入图片超过 $MAX_DOCX_IMAGES 张，超出部分未读取"
                        continue
                    }
                    val bytes = zip.getInputStream(entry).use { readLimited(it, 16 * 1024 * 1024, "$label（嵌入图片 $imageCount）", warnings) }
                    extractedBytes += bytes.size
                    if (extractedBytes > MAX_DOCX_BYTES) {
                        warnings += "$label：解压内容超过 100 MiB，剩余部分未读取"
                        break
                    }
                    val imageFile = File(cache, "${UUID.randomUUID()}.image").apply { writeBytes(bytes) }
                    parts += SourcePart.Image("$label · 嵌入图片 $imageCount", ImageSource.FileImage(imageFile))
                }
            }
            require(foundMain) { "Not a DOCX file" }
            if (text.isNotBlank()) addText(parts, label, text.toString(), warnings)
            else warnings += "$label：未提取到正文文字"
        }
    }

    private fun buildBatches(parts: List<SourcePart>): List<Batch> {
        val batches = mutableListOf<Batch>()
        val text = StringBuilder()
        val images = mutableListOf<SourcePart.Image>()
        val labels = mutableListOf<String>()
        fun flush() {
            if (text.isNotBlank() || images.isNotEmpty()) {
                batches += Batch(text.toString(), images.toList(), labels.toList())
                text.clear(); images.clear(); labels.clear()
            }
        }
        for (part in parts) when (part) {
            is SourcePart.Text -> {
                if (text.length + part.text.length > 12_000) flush()
                text.append(part.label).append('\n').append(part.text).append('\n')
                labels += part.label
            }
            is SourcePart.Image -> {
                if (images.size == 3) flush()
                images += part; labels += part.label
            }
        }
        flush()
        return batches
    }

    private fun normalizeImage(file: File): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 2400) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Cannot decode image")
        try { return jpeg(bitmap) } finally { bitmap.recycle() }
    }

    private fun renderPdfPage(file: File, index: Int): ByteArray {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    val scale = 1600f / max(page.width, page.height).coerceAtLeast(1)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, Matrix().apply { postScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        jpeg(bitmap)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val scale = minOf(1f, 1600f / max(bitmap.width, bitmap.height))
        val resized = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
        val flattened = if (resized.hasAlpha()) {
            Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.RGB_565).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawBitmap(resized, 0f, 0f, null)
            }
        } else resized
        try {
            return ByteArrayOutputStream().use { out ->
                flattened.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            if (flattened !== resized) flattened.recycle()
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun readLimited(input: InputStream, limit: Int, label: String, warnings: MutableList<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read == -1) return out.toByteArray()
            out.write(buffer, 0, read)
        }
        if (input.read() != -1) warnings += "$label：超过 ${limit / 1024 / 1024} MiB 读取安全上限，超出部分未读取"
        return out.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) { String(bytes, charset("GB18030")) }
    }

    private sealed interface SourcePart {
        data class Text(val label: String, val text: String) : SourcePart
        data class Image(val label: String, val source: ImageSource) : SourcePart
    }
    private sealed interface ImageSource {
        data class FileImage(val file: File) : ImageSource
        data class PdfPage(val file: File, val index: Int) : ImageSource
    }
    private data class Batch(val text: String, val images: List<SourcePart.Image>, val labels: List<String>)

    companion object {
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_LEGACY_WORD_BYTES = 32L * 1024 * 1024
        private const val MAX_TEXT_CHARS = 2_000_000
        private const val MAX_PDF_PAGES = 500
        private const val MAX_DOCX_IMAGES = 100
        private const val MAX_DOCX_BYTES = 100L * 1024 * 1024
    }
}
