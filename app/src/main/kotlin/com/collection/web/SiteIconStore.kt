package com.collection.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal fun interface SiteIconLoader { suspend fun load(url: String): Bitmap? }

/** Origin-keyed private cache; failed requests are cached too. No third-party icon endpoint. */
internal class SiteIconStore(context: Context, web: WebPageClient = WebPages.client,
    private val candidates: suspend (String) -> List<String> = web::iconCandidates,
    private val iconBytes: suspend (String) -> ByteArray = web::iconBytes) : SiteIconLoader {
    private val directory = File(context.applicationContext.cacheDir, "site-icons")
    private data class Cached(val bitmap: Bitmap, val until: Long)
    private val memory = object : LruCache<String, Cached>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached) = value.bitmap.byteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val requests = Semaphore(3)

    override suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val normalized = runCatching { publicWebUrl(url) }.getOrNull() ?: return@withContext null
        val key = MessageDigest.getInstance("SHA-256").digest(origin(normalized).toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        locks.getOrPut(key) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            memory.get(key)?.takeIf { now < it.until }?.let { return@withLock it.bitmap }
            directory.mkdirs()
            val file = File(directory, "$key.png")
            val miss = File(directory, "$key.miss")
            val cached = if (file.isFile && file.length() <= 512 * 1024) decodeSiteIcon(file.readBytes()) else null
            if (cached != null && now - file.lastModified() < WEEK) {
                memory.put(key, Cached(cached, file.lastModified() + WEEK)); return@withLock cached
            }
            if (miss.isFile && now - miss.lastModified() < HOUR) return@withLock cached
            requests.withPermit {
                try {
                    for (candidate in candidates(url).take(5)) {
                        val bitmap = try { decodeSiteIcon(iconBytes(candidate)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        if (bitmap != null) {
                            val atomic = AtomicFile(file)
                            val stream = atomic.startWrite()
                            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); atomic.finishWrite(stream) }
                            catch (e: Exception) { atomic.failWrite(stream); throw e }
                            miss.delete(); memory.put(key, Cached(bitmap, now + WEEK)); trim()
                            return@withPermit bitmap
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Preserve a stale icon when offline. */ }
                runCatching { miss.writeText(""); trim() }
                cached
            }
        }
    }

    private fun trim() {
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.(png|miss)")) }
            ?.sortedByDescending { it.lastModified() }?.drop(256)?.forEach { it.delete() }
    }

    companion object {
        private const val HOUR = 60 * 60 * 1000L
        private const val WEEK = 7 * 24 * HOUR
        @Volatile private var instance: SiteIconStore? = null
        @Synchronized fun get(context: Context): SiteIconStore {
            val path = File(context.applicationContext.cacheDir, "site-icons")
            return instance?.takeIf { it.directory == path } ?: SiteIconStore(context).also { instance = it }
        }
    }
}

internal fun decodeSiteIcon(bytes: ByteArray): Bitmap? {
    fun decode(data: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
    decode(bytes)?.let { return it }
    // ICO may contain PNG frames or uncompressed 24/32-bit DIB frames.
    if (bytes.size < 6) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.getShort(0).toInt() != 0 || buffer.getShort(2).toInt() != 1) return null
    val count = buffer.getShort(4).toInt() and 0xffff
    if (count !in 1..64 || 6 + count * 16 > bytes.size) return null
    val entries = (0 until count).map { 6 + it * 16 }.sortedByDescending { at ->
        val width = bytes[at].toInt() and 255; val height = bytes[at + 1].toInt() and 255
        (if (width == 0) 256 else width) * (if (height == 0) 256 else height)
    }
    for (at in entries) {
        val size = buffer.getInt(at + 8); val offset = buffer.getInt(at + 12)
        if (size <= 0 || offset < 6 + count * 16 || offset.toLong() + size > bytes.size) continue
        val frame = bytes.copyOfRange(offset, offset + size)
        (decode(frame) ?: decodeIconDib(frame))?.let { return it }
    }
    return null
}

private fun decodeIconDib(data: ByteArray): Bitmap? {
    if (data.size < 40) return null
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val header = b.getInt(0); val width = b.getInt(4); val fullHeight = b.getInt(8)
    val height = abs(fullHeight) / 2; val bits = b.getShort(14).toInt() and 0xffff
    if (header !in 40..data.size || width !in 1..256 || height !in 1..256 || bits !in listOf(24, 32) || b.getInt(16) != 0) return null
    val stride = ((width * bits + 31) / 32) * 4
    val maskStart = header + stride * height
    if (maskStart > data.size) return null
    val maskStride = ((width + 31) / 32) * 4
    val pixels = IntArray(width * height)
    var hasAlpha = false
    for (y in 0 until height) for (x in 0 until width) {
        val row = if (fullHeight > 0) height - 1 - y else y
        val offset = header + row * stride + x * (bits / 8)
        val alpha = if (bits == 32) data[offset + 3].toInt() and 255 else 255
        if (alpha != 0) hasAlpha = true
        pixels[y * width + x] = (alpha shl 24) or ((data[offset + 2].toInt() and 255) shl 16) or
            ((data[offset + 1].toInt() and 255) shl 8) or (data[offset].toInt() and 255)
    }
    for (y in 0 until height) for (x in 0 until width) {
        val row = if (fullHeight > 0) height - 1 - y else y
        val mask = maskStart + row * maskStride + x / 8
        val transparent = mask < data.size && (data[mask].toInt() and (0x80 shr (x % 8))) != 0
        val index = y * width + x
        if (transparent) pixels[index] = pixels[index] and 0x00ffffff
        else if (!hasAlpha) pixels[index] = pixels[index] or 0xff000000.toInt()
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
