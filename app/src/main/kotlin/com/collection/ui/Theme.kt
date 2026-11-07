@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collection.domain.AppearanceSettings
import com.collection.domain.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private val Type = Typography().let { type -> type.copy(
    displayMedium = type.displayMedium.copy(fontWeight = FontWeight.Bold),
    headlineLarge = type.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = type.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = type.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = type.titleMedium.copy(fontWeight = FontWeight.Medium),
) }

@Composable
fun CollTheme(
    dark: Boolean = isSystemInDarkTheme(),
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val style = remember(appearance.paletteStyle) {
        runCatching { PaletteStyle.valueOf(appearance.paletteStyle) }.getOrDefault(PaletteStyle.TonalSpot)
    }
    val spec = remember(appearance.colorSpec) {
        runCatching { ColorSpec.SpecVersion.valueOf(appearance.colorSpec) }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025)
    }
    val seeded = rememberDynamicColorScheme(
        seedColor = Color(appearance.seedColor),
        isDark = dark,
        style = style,
        specVersion = spec,
    )
    val wallpaper = if (appearance.dynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
    val base = wallpaper ?: seeded
    val colors = if (appearance.themeMode == ThemeMode.BLACK) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
        )
    } else {
        base
    }
    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme(colors), typography = Type,
        motionScheme = CollectionMotionScheme,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            largeIncreased = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
            extraLargeIncreased = RoundedCornerShape(32.dp), extraExtraLarge = RoundedCornerShape(36.dp)),
        content = content,
    )
}
