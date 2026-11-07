package com.collection

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.collection.data.PreferencesStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test fun standalonePackageOwnsLaunchersAndFileSharing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = app.packageManager
        assertEquals("com.collection", app.packageName)
        assertEquals("Collection", pm.getApplicationLabel(app.applicationInfo).toString())
        for (name in listOf("DynamicLauncher", "StaticLauncher")) {
            val component = android.content.ComponentName(app, "com.collection.$name")
            val info = pm.getActivityInfo(component, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(app.packageName, info.packageName)
            assertEquals("com.collection.MainActivity", info.targetActivity)
        }
        val provider = pm.resolveContentProvider("${app.packageName}.files", android.content.pm.PackageManager.GET_META_DATA)!!
        assertEquals(app.packageName, provider.packageName)
        assertEquals("com.collection.files", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(R.xml.file_paths, provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        assertNull(pm.resolveContentProvider("com.collfresh.files", 0))
        assertNull(pm.resolveContentProvider("com.collfresh.next.files", 0))
    }
    @Test fun launcherSwitchEnablesExactlyOneAliasAndPersistsItsChoice() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pm = app.packageManager
        val dynamic = android.content.ComponentName(app, "com.collection.DynamicLauncher")
        val fixed = android.content.ComponentName(app, "com.collection.StaticLauncher")
        LauncherIconMode.apply(app, false)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(dynamic))
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(fixed))
        val store = PreferencesStore(app)
        store.writeAppearance(store.readAppearance().copy(dynamicIcon = false))
        assertFalse(store.readAppearance().dynamicIcon)
        LauncherIconMode.apply(app, true)
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(dynamic))
        assertEquals(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(fixed))
    }
    @Test fun adaptiveIconHasBothColorLayersAndAThemedGlyphInLightAndDark() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val board = Bitmap.createBitmap(720, 290, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(245, 245, 245))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 20f; textAlign = Paint.Align.CENTER }
        for ((index, mode) in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).withIndex()) {
            val config = Configuration(app.resources.configuration).apply { uiMode = Configuration.UI_MODE_TYPE_NORMAL or mode }
            val context = app.createConfigurationContext(config)
            val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
            assertNotNull(icon.background); assertNotNull(icon.foreground); assertNotNull(icon.monochrome)
            icon.setBounds(index * 240 + 36, 28, index * 240 + 204, 196)
            icon.draw(canvas)
            canvas.drawText(if (index == 0) "Light" else "Dark", index * 240 + 120f, 240f, paint)
            if (index == 0) {
                val mono = icon.monochrome!!.mutate().apply { setTint(context.getColor(R.color.launcher_foreground)) }
                val themed = AdaptiveIconDrawable(ColorDrawable(context.getColor(R.color.launcher_background)), mono)
                themed.setBounds(516, 28, 684, 196); themed.draw(canvas)
                canvas.drawText("Themed", 600f, 240f, paint)
            }
        }
        File("build/reports/screenshots/launcher-redesign.png").apply {
            parentFile?.mkdirs(); outputStream().use { board.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test @Config(sdk = [31]) fun android12LoadsAdaptiveIconsAndWallpaperColors() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val modes = listOf(
            Configuration.UI_MODE_NIGHT_NO to android.R.color.system_accent1_100,
            Configuration.UI_MODE_NIGHT_YES to android.R.color.system_accent1_800,
        )
        for ((mode, systemBackground) in modes) {
            val config = Configuration(app.resources.configuration).apply { uiMode = mode }
            val context = app.createConfigurationContext(config)
            assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
            assertTrue(context.getDrawable(R.mipmap.ic_launcher_static) is AdaptiveIconDrawable)
            assertEquals(context.getColor(systemBackground), context.getColor(R.color.launcher_background))
            assertEquals(Color.parseColor("#D8F6E8"), context.getColor(R.color.launcher_static_background))
        }
    }
    @Test fun dynamicRedesignMigrationPreservesCustomSeedAndSubsequentOptOut() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("collfresh_settings", 0)
        prefs.edit().clear().putBoolean("appearance_dynamic_color", false)
            .putLong("appearance_seed_color", 0xFF8855AA).commit()
        val store = PreferencesStore(app)
        val migrated = store.readAppearance()
        assertTrue(migrated.dynamicColor)
        assertEquals(0xFF8855AA, migrated.seedColor)
        store.writeAppearance(migrated.copy(dynamicColor = false))
        assertFalse(PreferencesStore(app).readAppearance().dynamicColor)
    }
}
