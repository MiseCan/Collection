package com.collection.web

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WebReadFailure(message: String) : IOException(message)

internal class PublicWebDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicWebAddress(it) }) throw UnknownHostException("只支持公开网站")
        return addresses
    }
}

internal fun isPublicWebAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 4) {
        val a = b[0]; val c = b[1]; val d = b[2]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            a == 100 && c in 64..127 || a == 169 && c == 254 || a == 172 && c in 16..31 ||
            a == 192 && (c == 168 || c == 0 && d in listOf(0, 2) || c == 88 && d == 99) ||
            a == 198 && (c in 18..19 || c == 51 && d == 100) || a == 203 && c == 0 && d == 113)
    }
    return b.size == 16 && b[0] and 0xe0 == 0x20 && !(b.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8))
}

internal fun publicWebUrl(raw: String): HttpUrl {
    var url = raw.toHttpUrlOrNull() ?: throw WebReadFailure("网页地址无效")
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw WebReadFailure("不支持带登录信息的网页地址")
    if (!url.isHttps) url = url.newBuilder().scheme("https").apply { if (url.port == 80) port(443) }.build()
    val host = url.host.lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) throw WebReadFailure("只支持公开网站")
    if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
        if (!isPublicWebAddress(InetAddress.getByName(host))) throw WebReadFailure("只支持公开网站")
    }
    return url.newBuilder().fragment(null).build()
}

internal object WebPages { val client = WebPageClient() }

/** Separate, credential-free transport. Redirect targets and actual DNS answers are checked. */
internal class WebPageClient(http: OkHttpClient = OkHttpClient.Builder().dns(PublicWebDns()).build()) {
    private val http = http.newBuilder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private data class Hints(val icons: List<String>, val time: Long)
    private val hints = ConcurrentHashMap<String, Hints>()
    private data class Payload(val url: HttpUrl, val bytes: ByteArray, val mime: String, val charset: String?, val partial: Boolean)

    suspend fun readPage(raw: String): WebPage {
        val data = fetch(raw, 2 * 1024 * 1024, image = false)
        val page = parseWebPage(data.bytes, data.url, data.charset, data.partial)
        rememberIcons(publicWebUrl(raw), page.icons)
        if (page.text.length < 80) throw WebReadFailure("未提取到正文，可能需要登录或动态加载")
        return page
    }

    suspend fun iconCandidates(raw: String): List<String> {
        val url = publicWebUrl(raw)
        hints[origin(url)]?.takeIf { System.currentTimeMillis() - it.time < 60 * 60 * 1000 }?.let { return it.icons }
        return try {
            val data = fetch(raw, 256 * 1024, image = false)
            val icons = parseWebPage(data.bytes, data.url, data.charset, data.partial).icons
            rememberIcons(url, icons); icons
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: IOException) { listOf(url.resolve("/favicon.ico").toString()) }
    }

    suspend fun iconBytes(raw: String): ByteArray = fetch(raw, 512 * 1024, image = true).bytes

    private fun rememberIcons(url: HttpUrl, icons: List<String>) {
        if (hints.size >= 128) hints.keys.firstOrNull()?.let(hints::remove)
        hints[origin(url)] = Hints(icons, System.currentTimeMillis())
    }

    private suspend fun fetch(raw: String, limit: Int, image: Boolean): Payload {
        var url = publicWebUrl(raw)
        val visited = mutableSetOf<String>()
        for (hop in 0..5) {
            if (!visited.add(url.toString())) throw WebReadFailure("网页发生重定向循环")
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (compatible; Collection/1.0)")
                .header("Accept", if (image) "image/*" else "text/html,application/xhtml+xml").get().build()
            val response = try { http.newCall(request).awaitWeb() }
                catch (e: IOException) { throw WebReadFailure("网页连接失败或超时") }
            response.use {
                if (it.code in listOf(301, 302, 303, 307, 308)) {
                    val next = it.header("Location")?.let(url::resolve) ?: throw WebReadFailure("网页重定向地址无效")
                    if (!next.isHttps) throw WebReadFailure("网页重定向未使用HTTPS")
                    url = publicWebUrl(next.toString())
                } else {
                    if (!it.isSuccessful) throw WebReadFailure(when (it.code) {
                        401, 403 -> "需要登录或站点限制访问"; 429 -> "网站访问频率受限，请稍后重试"; else -> "网页读取失败（HTTP ${it.code}）"
                    })
                    val body = it.body ?: throw WebReadFailure("网页返回空内容")
                    val media = body.contentType()
                    val mime = media?.let { type -> "${type.type}/${type.subtype}" }.orEmpty().lowercase()
                    if (image && mime.contains("html") || !image && mime !in listOf("text/html", "application/xhtml+xml", "")) throw WebReadFailure("链接不是可读取的网页或图标")
                    val bytes = body.byteStream().use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= limit) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    if (image && bytes.size > limit) throw WebReadFailure("网站图标过大")
                    return Payload(url, bytes.copyOf(minOf(limit, bytes.size)), mime, media?.charset()?.name(), bytes.size > limit)
                }
            }
        }
        throw WebReadFailure("网页重定向过多")
    }
}

internal fun origin(url: HttpUrl): String = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()

private suspend fun Call.awaitWeb(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
