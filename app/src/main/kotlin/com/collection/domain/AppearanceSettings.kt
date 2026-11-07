package com.collection.domain

/** App-wide theme, motion and scale preferences. */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedColor: Long = 0xFF006B5E,
    val paletteStyle: String = "TonalSpot",
    val colorSpec: String = "SPEC_2025",
    val dynamicColor: Boolean = true,
    val dynamicIcon: Boolean = true,
    val predictiveBack: Boolean = true,
    val edgeAwareBack: Boolean = true,
    val pageTransition: PageTransitionStyle = PageTransitionStyle.MIUIX,
    val uiScale: Float = 1f,
)

enum class ThemeMode {
    SYSTEM, LIGHT, DARK, BLACK;

    val isDark: Boolean get() = this == DARK || this == BLACK
}

enum class PageTransitionStyle {
    /** KernelSU/Miuix layered slide: full-width top page plus 25% lower-page parallax. */
    MIUIX,

    /** A quiet cross-fade for users who prefer no horizontal motion. */
    FADE,
}
