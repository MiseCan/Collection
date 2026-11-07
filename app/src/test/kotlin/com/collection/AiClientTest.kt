package com.collection

import com.collection.ai.DeepSeekClient
import com.collection.domain.AiConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.net.Proxy

/** Real local HTTPS requests only; no external endpoint or real API key is used. */
class AiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var config: AiConfig
    private val delays = mutableListOf<Long>()
    private lateinit var client: DeepSeekClient

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start()
        }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .readTimeout(5, TimeUnit.SECONDS).build()
        client = DeepSeekClient(http) { delays += it }
        config = AiConfig("test-only-token", server.url("/chat/completions").toString(), "deepseek-flash", proxyMode = true)
    }

    @After fun teardown() {
        if (::http.isInitialized) {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun sendsBearerAndJsonOutputAndReadsFields() = runBlocking {
        server.enqueue(validResponse())
        val result = client.summarize("原始资料事实", emptyList(), config, final = true, onRetry = {})
        assertEquals("资料标题", result.title)
        assertEquals("资料标题", result.title)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer test-only-token", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("deepseek-flash", payload.getString("model"))
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"))
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
        assertEquals(128, payload.getInt("max_tokens"))
        val prompt = payload.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.length < 100)
        assertFalse(prompt.contains("summary"))
        val data = payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(data.contains("原始资料事实"))
        assertTrue(delays.isEmpty())
    }

    @Test fun retriesRateLimitAndBlankContentWithBoundedBackoff() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
        server.enqueue(response("   "))
        server.enqueue(validResponse())
        val notices = mutableListOf<String>()
        val result = client.summarize("事实", emptyList(), config, true) { notices += it }
        assertEquals("资料标题", result.title)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2000L, 2000L), delays)
        assertEquals(2, notices.size)
    }

    @Test fun authFailureNeverRetriesOrEchoesBodyOrToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-only-token secret details"))
        val exception = runCatching { client.summarize("事实", emptyList(), config, true, {}) }.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception!!.message.orEmpty().contains("鉴权"))
        assertFalse(exception.message.orEmpty().contains("test-only-token"))
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test fun incompleteJsonRetriesRatherThanAcceptingPartialResult() = runBlocking {
        server.enqueue(response("{\"title\":\"资料标题\"}", "length"))
        server.enqueue(validResponse())
        val result = client.summarize("事实", emptyList(), config, true, {})
        assertEquals("资料标题", result.title)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(1000L), delays)
    }

    @Test fun cancellationStopsCallWithoutRetry() = runBlocking {
        server.enqueue(validResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch {
            client.summarize("事实", emptyList(), config, true, {})
        }
        // Let the coroutine enqueue before blocking for the server's recorded request.
        kotlinx.coroutines.yield()
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    private fun validResponse(): MockResponse = response("{\"title\":\"资料标题\"}")
    private fun response(content: String, finish: String = "stop"): MockResponse = MockResponse()
        .setResponseCode(200).addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
            .put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString())
}
