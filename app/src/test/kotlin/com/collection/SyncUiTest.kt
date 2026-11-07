package com.collection

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.collection.sync.SyncConfig
import com.collection.sync.SyncStatus
import com.collection.ui.CollTheme
import com.collection.ui.SyncSettingsScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun configurationValidationAndManualSyncRemainDistinct() {
        var saved by mutableStateOf(SyncConfig())
        var syncs = 0; var tests = 0
        compose.setContent { CollTheme { SyncSettingsScreen(saved, SyncStatus(), false, {}, { saved = it }, { tests++ }, { syncs++ }) } }
        compose.onNodeWithText("立即同步").assertIsNotEnabled()
        compose.onNodeWithText("账号邮箱").performTextInput("me@example.com")
        compose.onNodeWithText("应用密码").performTextInput("test-app-password")
        compose.onNodeWithText("测试连接").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, tests); assertEquals(0, syncs) }
        compose.onNodeWithText("自动双向同步").performScrollTo().performClick()
        compose.onNodeWithText("保存同步设置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("me@example.com", saved.username); assertTrue(saved.automatic); assertTrue(saved.wifiOnly) }
        compose.onNodeWithText("立即同步").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, syncs) }
        screenshot("sync-configured-light")
    }
    @Test fun invalidServerAndUnsavedBackProtectConfiguration() {
        var back = 0; var saved = 0
        compose.setContent { CollTheme { SyncSettingsScreen(SyncConfig(), SyncStatus(), false, { back++ }, { saved++ }, {}, {}) } }
        compose.onNodeWithText("服务器地址（HTTPS）").performTextReplacement("http://invalid.example/")
        compose.onNodeWithText("保存同步设置").performScrollTo().performClick()
        compose.onNodeWithText("请填写有效的 HTTPS WebDAV 地址").assertExists()
        compose.runOnIdle { assertEquals(0, saved) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃未保存的设置？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals(0, back) }
    }
    @Test fun darkThemeShowsProgressAndLastSuccessWithoutEnablingDuplicateSync() {
        compose.setContent { CollTheme(dark = true) { SyncSettingsScreen(
            SyncConfig(username = "me@example.com", password = "test-app-password"),
            SyncStatus(true, "正在上传原件 1/3…", 1_800_000_000_000), false, {}, {}, {}, {}) } }
        compose.onNodeWithText("正在上传原件 1/3…").assertIsDisplayed()
        compose.onNodeWithText("立即同步").assertIsNotEnabled()
        compose.onNodeWithText("上次成功：", substring = true).assertIsDisplayed()
        screenshot("sync-progress-dark")
    }
    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/reports/screenshots/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
