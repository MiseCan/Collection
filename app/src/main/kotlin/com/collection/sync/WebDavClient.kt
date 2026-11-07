package com.collection.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RemoteHead(val actor: String, val etag: String?)

/** Restricted to one authenticated origin. Redirects never forward the WebDAV credential. */
internal class WebDavClient(
    config: SyncConfig,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build(),
    private val reserve: () -> Unit = {},
) {
    private val config = config.validated()
    private val base = this.config.endpoint.toHttpUrl()
    private val useDirectPut = base.host == "dav.jianguoyun.com"
    private val root = base.newBuilder().addPathSegments("Collection/v1/").build()
    private val credential = Credentials.basic(this.config.username, this.config.password, Charsets.UTF_8)
    private fun url(path: String) = root.newBuilder().addPathSegments(path).build()

    suspend fun initialize() {
        for (path in listOf("Collection/", "Collection/v1/", "Collection/v1/devices/", "Collection/v1/objects/")) {
            ensureCollection(base.newBuilder().addPathSegments(path).build())
        }
    }

    /** MKCOL admits one writer. The owner marker lets the same device recover after a restart. */
    suspend fun <T> withWriteLock(actor: String, action: suspend () -> T): T {
        require(ACTOR.matches(actor))
        val lock = url("write-lock/")
        val owner = url("write-lock/$actor.txt")
        val alternatives = listOf(lock, lock.newBuilder().encodedPath(lock.encodedPath.trimEnd('/')).build())
        val created = alternatives.firstOrNull { form ->
            request("MKCOL", form, allowed = setOf(201, 403, 404, 405, 409)).use { it.code == 201 }
        }
        if (created == null && !request("HEAD", owner, allowed = setOf(200, 404, 409)).use { it.code == 200 }) {
            throw SyncFailure("其他设备正在同步，请稍后重试", retryable = true)
        }
        try {
            if (created != null) request("PUT", owner, actor.toRequestBody(BINARY), allowed = setOf(200, 201, 204)).close()
            return action()
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) {
                    val forms = if (created == null) alternatives else listOf(created)
                    val removed = removeWriteLock(forms)
                    if (!removed) {
                        deleteTemporaryFile(owner)
                        removeWriteLock(forms)
                    }
                }
            }
        }
    }

    private suspend fun removeWriteLock(forms: List<HttpUrl>): Boolean = forms.any { form ->
        request("DELETE", form, allowed = setOf(200, 204, 403, 404, 405, 409)).use { it.code in setOf(200, 204) }
    }

    private suspend fun ensureCollection(directory: HttpUrl) {
        val expectedPath = directory.encodedPath.trimEnd('/')
        // Some servers require the final slash on MKCOL and others reject it.
        val forms = listOf(directory, directory.newBuilder().encodedPath(expectedPath).build())
        for (form in forms) {
            val status = request("MKCOL", form, allowed = setOf(201, 403, 404, 405, 409)).use { it.code }
            if (status == 201 || isCollection(form, expectedPath)) return
        }
        val guidance = if (base.host == "dav.jianguoyun.com" && expectedPath == "/dav/Collection") {
            "请在坚果云的文件列表顶层新建 Collection 文件夹，再重试"
        } else "请检查该目录的上一级是否存在，以及应用密码是否有创建权限"
        throw SyncFailure("无法创建云端目录 $expectedPath。$guidance")
    }

    private suspend fun isCollection(url: HttpUrl, expectedPath: String): Boolean =
        request("PROPFIND", url, XML.toRequestBody(XML_TYPE), mapOf("Depth" to "0"),
            setOf(207, 403, 404, 405, 409)).use { response ->
            response.code == 207 && parseProperties(readBounded(response, 256 * 1024).toString(Charsets.UTF_8)).any { property ->
                val href = url.resolve(property.href)
                property.collection && href != null && href.host == url.host && href.port == url.port &&
                    href.scheme == url.scheme && href.encodedPath.trimEnd('/') == expectedPath
            }
        }

    suspend fun test() {
        request("PROPFIND", base, XML.toRequestBody(XML_TYPE), mapOf("Depth" to "0"), setOf(207)).use {
            val xml = readBounded(it, 256 * 1024).toString(Charsets.UTF_8)
            require(parseProperties(xml).any { p -> p.collection }) { "此地址不是 WebDAV 文件夹" }
        }
        initialize()
        val name = "connection-${UUID.randomUUID()}"
        val content = "Collection WebDAV connection ${UUID.randomUUID()}".toByteArray()
        val target = url("devices/$name.txt")
        val staging = url("devices/$name.tmp")
        try {
            publishFile(target, staging, content.toRequestBody(BINARY), overwrite = false)
            request("GET", target).use { require(readBounded(it, 1024).contentEquals(content)) { "WebDAV 读写校验失败" } }
            val updated = content + byteArrayOf(1)
            publishFile(target, staging, updated.toRequestBody(BINARY), overwrite = true)
            request("GET", target).use { require(readBounded(it, 1024).contentEquals(updated)) { "WebDAV 更新校验失败" } }
        } finally {
            deleteTemporaryFile(target)
        }
    }

    suspend fun heads(): List<RemoteHead> {
        val devices = url("devices/")
        return request("PROPFIND", devices, XML.toRequestBody(XML_TYPE), mapOf("Depth" to "1"), setOf(207)).use { response ->
            val properties = parseProperties(readBounded(response, 2 * 1024 * 1024).toString(Charsets.UTF_8))
            val hasNextPage = response.headers.values("Link").any { it.contains("rel=\"next\"", true) || it.contains("rel=next", true) }
            require(properties.size < 750 && !hasNextPage) { "云端设备目录返回不完整，已停止同步以避免遗漏" }
            properties.mapNotNull { p ->
                val resolved = devices.resolve(p.href) ?: throw IOException("WebDAV 返回了无效地址")
                require(resolved.scheme == devices.scheme && resolved.host == devices.host && resolved.port == devices.port) { "WebDAV 返回了不同服务器的地址" }
                if (resolved.encodedPath.trimEnd('/') == devices.encodedPath.trimEnd('/')) return@mapNotNull null
                require(resolved.encodedPath.startsWith(devices.encodedPath)) { "WebDAV 返回了目录以外的文件" }
                val name = resolved.encodedPath.removePrefix(devices.encodedPath)
                if (p.collection || !name.endsWith(".json")) null
                else {
                    val actor = name.removeSuffix(".json")
                    require(ACTOR.matches(actor)) { "设备同步文件名无效" }
                    RemoteHead(actor, p.etag)
                }
            }.distinctBy { it.actor }
        }
    }

    suspend fun readHead(actor: String): SyncDocument = request("GET", url("devices/$actor.json")).use {
        SyncDocument.decode(readBounded(it, MAX_SYNC_BYTES).toString(Charsets.UTF_8))
    }

    suspend fun uploadBlob(hash: String, file: File) {
        require(HASH.matches(hash) && file.length() <= MAX_ORIGINAL_BYTES)
        ensureCollection(url("objects/${hash.take(2)}/"))
        request("PUT", url("objects/${hash.take(2)}/$hash.bin"), file.asRequestBody(BINARY), allowed = setOf(200, 201, 204)).close()
    }

    suspend fun blobExists(hash: String): Boolean {
        require(HASH.matches(hash))
        return request("HEAD", url("objects/${hash.take(2)}/$hash.bin"), allowed = setOf(200, 404, 409)).use { it.code == 200 }
    }

    suspend fun deleteBlob(hash: String) {
        require(HASH.matches(hash))
        request("DELETE", url("objects/${hash.take(2)}/$hash.bin"), allowed = setOf(200, 204, 404)).close()
    }

    suspend fun downloadBlob(hash: String, size: Long, target: File) {
        require(HASH.matches(hash) && size in 0..MAX_ORIGINAL_BYTES)
        try {
            request("GET", url("objects/${hash.take(2)}/$hash.bin")).use { response ->
                val body = response.body ?: throw IOException("原件响应为空")
                require(body.contentLength() < 0 || body.contentLength() == size) { "云端原件长度不符" }
                body.byteStream().use { input -> target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= size) { "云端原件超过声明大小" }; output.write(buffer, 0, count)
                    }
                    require(total == size) { "原件下载不完整" }
                } }
            }
            require(fileHash(target) == hash) { "原件校验失败，未导入本机" }
        } catch (e: Throwable) { target.delete(); throw e }
    }

    suspend fun publish(actor: String, document: SyncDocument) {
        require(ACTOR.matches(actor))
        val text = document.encode().toByteArray()
        require(text.size <= MAX_SYNC_BYTES) { "同步目录过大" }
        val staging = url("devices/$actor-${UUID.randomUUID()}.tmp")
        publishFile(url("devices/$actor.json"), staging, text.toRequestBody(JSON), overwrite = true)
        require(readHead(actor).encode() == document.encode()) { "云端同步目录写入校验失败，请重试" }
    }

    private suspend fun publishFile(target: HttpUrl, staging: HttpUrl, body: RequestBody, overwrite: Boolean) {
        // Nutstore can reject MOVE even when PUT works. Each device owns its own destination.
        if (useDirectPut) {
            request("PUT", target, body, allowed = setOf(200, 201, 204)).close()
            return
        }
        try {
            request("PUT", staging, body, allowed = setOf(200, 201, 204)).close()
            request("MOVE", staging, headers = mapOf("Destination" to target.toString(), "Overwrite" to if (overwrite) "T" else "F"),
                allowed = setOf(201, 204)).close()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            deleteTemporaryFile(staging)
            throw e
        }
    }

    private suspend fun deleteTemporaryFile(file: HttpUrl) {
        try { request("DELETE", file, allowed = setOf(200, 204, 404)).close() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }

    private suspend fun request(method: String, url: HttpUrl, body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(), allowed: Set<Int> = setOf(200)): Response {
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            withContext(Dispatchers.IO) { reserve() }
            val request = Request.Builder().url(url).header("Authorization", credential)
                .header("User-Agent", "Collection/1.0 WebDAV").apply { headers.forEach { (name, value) -> header(name, value) } }
                .method(method, if (method in listOf("PUT", "POST") && body == null) ByteArray(0).toRequestBody() else body).build()
            val call = client.newCall(request)
            val response = suspendCancellableCoroutine<Response> { c ->
                c.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(SyncFailure("网络连接失败，请检查连接后重试", true, e)) }
                    override fun onResponse(call: Call, response: Response) { c.resume(response) { _, value, _ -> value.close() } }
                })
            }
            if (response.code in allowed) return response
            val code = response.code
            val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: (attempt + 1) * 2L
            response.close()
            if (code in listOf(429, 500, 502, 503, 504) && attempt < 2) delay(waitSeconds * 1000)
            else throw SyncFailure(when (code) {
                401 -> "账号或应用密码不正确，请重新配置"
                403 -> "云端拒绝此操作，请检查目录权限或流量额度"
                404 -> "云端文件不存在，请检查同步目录是否被移动或删除"
                409 -> "云端拒绝 $method 操作（HTTP 409）。路径 ${url.encodedPath}"
                429 -> "云盘请求过于频繁，稍后会自动重试"
                507 -> "云盘空间或上传额度不足"
                in 300..399 -> "WebDAV 地址发生跳转，请填写最终 HTTPS 地址"
                else -> "WebDAV 操作失败（$code）"
            }, code in listOf(429, 500, 502, 503, 504))
        }
        throw IOException("同步请求失败")
    }

    private suspend fun readBounded(response: Response, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val body = response.body ?: throw IOException("同步响应为空")
        require(body.contentLength() <= limit) { "云端响应超过安全上限" }
        body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
            while (true) { coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= limit) { "云端响应过大" }; out.write(buffer, 0, n) }
            out.toByteArray()
        }
    }

    private data class Property(val href: String, val etag: String?, val collection: Boolean)
    private fun parseProperties(text: String): List<Property> {
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "WebDAV XML 包含不支持的声明" }
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(text.reader())
        val result = mutableListOf<Property>()
        var href = ""; var etag: String? = null; var collection = false; var inResponse = false; var multistatus = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.namespace == "DAV:") when (parser.name) {
                "multistatus" -> multistatus = true
                "response" -> { inResponse = true; href = ""; etag = null; collection = false }
                "href" -> if (inResponse) href = parser.nextText()
                "getetag" -> if (inResponse) etag = parser.nextText().trim().takeIf { it.isNotEmpty() }
                "collection" -> if (inResponse) collection = true
                "status" -> if (inResponse) {
                    val status = parser.nextText().split(' ').getOrNull(1)?.toIntOrNull()
                    require(status == null || status in 200..299 || status == 404) { "WebDAV 目录返回部分错误，已停止同步" }
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && parser.namespace == "DAV:" && parser.name == "response") {
                require(href.isNotBlank()) { "WebDAV 目录缺少文件地址" }
                result += Property(href, etag, collection); inResponse = false
            }
            parser.next()
        }
        require(multistatus && result.isNotEmpty()) { "服务器未返回完整 WebDAV 目录" }
        return result
    }

    private companion object {
        val XML_TYPE = "application/xml; charset=utf-8".toMediaType()
        val JSON = "application/json; charset=utf-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
        const val XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getetag/></d:prop></d:propfind>"
    }
}
