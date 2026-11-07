package com.collection

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collection.domain.ThemeMode
import com.collection.data.TrashCleanupWorker
import com.collection.ui.AppRoot
import com.collection.ui.CollTheme
import com.collection.ui.LocalSiteIcons
import com.collection.web.SiteIconStore

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrashCleanupWorker.schedule(applicationContext)
        enableEdgeToEdge()
        setContent {
            val ui by model.uiState.collectAsStateWithLifecycle()
            val appearance = ui.appearance
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.SYSTEM -> systemDark
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val density = LocalDensity.current
            val siteIcons = remember(applicationContext) { SiteIconStore.get(applicationContext) }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * appearance.uiScale, density.fontScale),
                LocalSiteIcons provides siteIcons,
            ) {
                CollTheme(dark = dark, appearance = appearance) { AppRoot(model) }
            }
        }
        if (savedInstanceState == null) handleShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }
    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent) {
        readSharePayload(intent)?.let(model::receiveShare)
    }
}

@Suppress("DEPRECATION")
internal fun readSharePayload(intent: Intent): SharePayload? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            } else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
            val clip = intent.clipData
            if (clip != null) for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it) }
        }.distinct().filter { it.scheme == "content" }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        return if (uris.isNotEmpty() || text.isNotBlank()) SharePayload(text, uris) else null
}
