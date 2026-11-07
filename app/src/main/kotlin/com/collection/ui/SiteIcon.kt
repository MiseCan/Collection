package com.collection.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.collection.domain.EntryIcon
import com.collection.web.SiteIconLoader
import kotlinx.coroutines.CancellationException

// The Activity provides the real loader. Previews and isolated UI tests remain offline by default.
internal val LocalSiteIcons = staticCompositionLocalOf<SiteIconLoader?> { null }

@Composable
internal fun rememberSiteIcon(url: String?): State<Bitmap?> {
    val loader = LocalSiteIcons.current
    return produceState<Bitmap?>(null, url, loader) {
        value = null
        if (url != null && loader != null) value = try { loader.load(url) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
    }
}

@Composable
internal fun SiteIcon(url: String, size: Dp = 24.dp) {
    val bitmap by rememberSiteIcon(url)
    MotionSwap(bitmap, Modifier.size(size)) { icon ->
        if (icon != null) Image(icon.asImageBitmap(), "网站图标", Modifier.size(size), contentScale = ContentScale.Fit)
        else EntryTypeIcon(EntryIcon.LINK, Modifier.size(size))
    }
}
