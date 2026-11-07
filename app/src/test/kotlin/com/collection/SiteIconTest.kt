package com.collection

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.collection.domain.Entry
import com.collection.domain.HomePreferences
import com.collection.domain.Library
import com.collection.ui.*
import com.collection.web.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SiteIconTest {
    @get:Rule val compose = createComposeRule()
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }

    @Test fun cachesIconsByOriginInMemoryAndOnDisk() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bytes = png(); var fetched = 0
        fun store() = SiteIconStore(app, candidates = { listOf("https://cache.example/icon.png") }, iconBytes = { fetched++; bytes })
        val icons = store()
        assertNotNull(icons.load("https://cache.example/one"))
        assertNotNull(icons.load("https://cache.example/two?different=query"))
        assertEquals(1, fetched)
        val reopened = store().load("https://cache.example/three")!!
        assertEquals(android.graphics.Color.GREEN, reopened.getPixel(20, 20)); assertEquals(1, fetched)
    }

    @Test fun failedIconsAreCachedAndCancellationDoesNotPoisonTheCache() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var attempts = 0
        val unavailable = SiteIconStore(app, candidates = { listOf("https://failed.example/icon.png") }, iconBytes = { attempts++; throw IOException("offline") })
        assertNull(unavailable.load("https://failed.example/one")); assertNull(unavailable.load("https://failed.example/two")); assertEquals(1, attempts)
        var waiting = true
        val started = CompletableDeferred<Unit>()
        val icon = png()
        val cancellable = SiteIconStore(app, candidates = {
            if (waiting) { started.complete(Unit); awaitCancellation() }
            listOf("https://cancel.example/icon.png")
        }, iconBytes = { icon })
        val job = launch(Dispatchers.Default) { cancellable.load("https://cancel.example") }
        withTimeout(2000) { started.await() }; job.cancelAndJoin(); waiting = false
        assertNotNull(cancellable.load("https://cancel.example"))
    }

    @Test fun decodesPngFramesAndDibFaviconsAndRejectsCorruptOffsets() {
        fun ico(frame: ByteArray, width: Int = 64, height: Int = 64): ByteArray = ByteBuffer.allocate(22 + frame.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(1); put(width.toByte()); put(height.toByte()); put(0); put(0)
            putShort(1); putShort(32); putInt(frame.size); putInt(22); put(frame)
        }.array()
        assertEquals(android.graphics.Color.GREEN, decodeSiteIcon(ico(png()))!!.getPixel(20, 20))
        val dib = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 40); putInt(4, 1); putInt(8, 2); putShort(12, 1); putShort(14, 32)
            put(42, 255.toByte()); put(43, 255.toByte())
        }.array()
        assertEquals(android.graphics.Color.RED, decodeSiteIcon(ico(dib, 1, 1))!!.getPixel(0, 0))
        val corrupt = ico(dib).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(18, Int.MAX_VALUE) }
        assertNull(decodeSiteIcon(corrupt))
    }

    @Test fun websiteIconReplacesTheTextBadgeInHomeAndDetailWhileKeepingTheBackground() {
        val bytes = png(); val icon = decodeSiteIcon(bytes)!!
        val entry = Entry(id = "website", title = "网页研究资料", bodyTitle = "网页正文标题", body = "我的笔记 https://article.example/guide")
        var detail by mutableStateOf(false)
        compose.setContent { CompositionLocalProvider(LocalSiteIcons provides SiteIconLoader { icon }) {
            CollTheme {
                if (detail) DetailScreen(entry, emptyList(), { detail = false }, {}, {})
                else HomeScreen(Library(entries = listOf(entry)), HomePreferences(), "", {}, {}, { detail = true }, {}, {})
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("网站图标", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("entry_website").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("网站图标").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("网页正文标题").assertExists()
        compose.onNodeWithText("article.example").assertExists()
        compose.waitForIdle()
        File("build/reports/screenshots/detail-website-icons.png").apply {
            parentFile?.mkdirs(); outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
