package com.collection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.collection.ai.AiService
import com.collection.ai.DeepSeekClient
import com.collection.domain.AiConfig
import com.collection.domain.Attachment
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LegacyWordTitleTest {
    @Test fun word97BodyIsUsedForTitleAndOriginalIsUnchanged() = runBlocking {
        verifyTitle(sample("simple.doc"), "资料.DOC", "application/octet-stream",
            expectedText = "This is a simple file created with Word 97-SR2.")
    }

    @Test fun word6BodyIsReadWhenOnlyMimeIdentifiesTheFormat() = runBlocking {
        verifyTitle(sample("Word6.doc"), "旧资料", "application/msword",
            expectedText = "The quick brown fox jumps over the lazy dog")
    }

    @Test fun damagedDocWarnsAndOtherTextCanStillGenerateATitle() = runBlocking {
        verifyTitle("damaged file".toByteArray(), "损坏.doc", "application/msword",
            expectedText = "可读取的条目文字", expectWarning = true)
    }

    // Binary fixtures from Apache POI REL_6_0_0, test-data/document/.
    private fun sample(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/word/$name")).use { it.readBytes() }

    private suspend fun verifyTitle(bytes: ByteArray, name: String, mime: String,
        expectedText: String, expectWarning: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originals = File(context.filesDir, "originals").apply { mkdirs() }
        val file = File.createTempFile("doc-", ".bin", originals).apply { writeBytes(bytes) }
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1")
            .addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
        try {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.enqueue(MockResponse().setBody(
                    """{"choices":[{"finish_reason":"stop","message":{"content":"{\"title\":\"资料标题\"}"}}]}"""
                ))
                val result = AiService(context, DeepSeekClient(http)).generate(
                    if (expectWarning) expectedText else "",
                    listOf(Attachment(name = name, mime = mime, size = bytes.size.toLong(), path = file.path)),
                    AiConfig("test-token", server.url("/chat/completions").toString(), proxyMode = true),
                )

                assertEquals("资料标题", result.title)
                if (expectWarning) assertTrue(result.warnings.single().contains(name))
                else assertTrue(result.warnings.toString(), result.warnings.isEmpty())
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val content = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    .getJSONObject(1).getJSONArray("content")
                assertEquals(1, content.length())
                val text = content.getJSONObject(0).getString("text")
                assertTrue(text, text.contains(expectedText))
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            file.delete()
        }
    }
}
