@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.collection.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val CollectionMotionScheme = MotionScheme.expressive()

/** Interpolate every public color role together, without changing the target palette. */
@Composable
internal fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val effects = CollectionMotionScheme.defaultEffectsSpec<Color>()
    return target.copy(
        primary = animateColorAsState(target.primary, effects, label = "theme_primary").value,
        onPrimary = animateColorAsState(target.onPrimary, effects, label = "theme_onPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, effects, label = "theme_primaryContainer").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, effects, label = "theme_onPrimaryContainer").value,
        inversePrimary = animateColorAsState(target.inversePrimary, effects, label = "theme_inversePrimary").value,
        secondary = animateColorAsState(target.secondary, effects, label = "theme_secondary").value,
        onSecondary = animateColorAsState(target.onSecondary, effects, label = "theme_onSecondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, effects, label = "theme_secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, effects, label = "theme_onSecondaryContainer").value,
        tertiary = animateColorAsState(target.tertiary, effects, label = "theme_tertiary").value,
        onTertiary = animateColorAsState(target.onTertiary, effects, label = "theme_onTertiary").value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, effects, label = "theme_tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, effects, label = "theme_onTertiaryContainer").value,
        background = animateColorAsState(target.background, effects, label = "theme_background").value,
        onBackground = animateColorAsState(target.onBackground, effects, label = "theme_onBackground").value,
        surface = animateColorAsState(target.surface, effects, label = "theme_surface").value,
        onSurface = animateColorAsState(target.onSurface, effects, label = "theme_onSurface").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, effects, label = "theme_surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, effects, label = "theme_onSurfaceVariant").value,
        surfaceTint = animateColorAsState(target.surfaceTint, effects, label = "theme_surfaceTint").value,
        inverseSurface = animateColorAsState(target.inverseSurface, effects, label = "theme_inverseSurface").value,
        inverseOnSurface = animateColorAsState(target.inverseOnSurface, effects, label = "theme_inverseOnSurface").value,
        error = animateColorAsState(target.error, effects, label = "theme_error").value,
        onError = animateColorAsState(target.onError, effects, label = "theme_onError").value,
        errorContainer = animateColorAsState(target.errorContainer, effects, label = "theme_errorContainer").value,
        onErrorContainer = animateColorAsState(target.onErrorContainer, effects, label = "theme_onErrorContainer").value,
        outline = animateColorAsState(target.outline, effects, label = "theme_outline").value,
        outlineVariant = animateColorAsState(target.outlineVariant, effects, label = "theme_outlineVariant").value,
        scrim = animateColorAsState(target.scrim, effects, label = "theme_scrim").value,
        surfaceBright = animateColorAsState(target.surfaceBright, effects, label = "theme_surfaceBright").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, effects, label = "theme_surfaceContainer").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, effects, label = "theme_surfaceContainerHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, effects, label = "theme_surfaceContainerHighest").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, effects, label = "theme_surfaceContainerLow").value,
        surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, effects, label = "theme_surfaceContainerLowest").value,
        surfaceDim = animateColorAsState(target.surfaceDim, effects, label = "theme_surfaceDim").value,
        primaryFixed = animateColorAsState(target.primaryFixed, effects, label = "theme_primaryFixed").value,
        primaryFixedDim = animateColorAsState(target.primaryFixedDim, effects, label = "theme_primaryFixedDim").value,
        onPrimaryFixed = animateColorAsState(target.onPrimaryFixed, effects, label = "theme_onPrimaryFixed").value,
        onPrimaryFixedVariant = animateColorAsState(target.onPrimaryFixedVariant, effects, label = "theme_onPrimaryFixedVariant").value,
        secondaryFixed = animateColorAsState(target.secondaryFixed, effects, label = "theme_secondaryFixed").value,
        secondaryFixedDim = animateColorAsState(target.secondaryFixedDim, effects, label = "theme_secondaryFixedDim").value,
        onSecondaryFixed = animateColorAsState(target.onSecondaryFixed, effects, label = "theme_onSecondaryFixed").value,
        onSecondaryFixedVariant = animateColorAsState(target.onSecondaryFixedVariant, effects, label = "theme_onSecondaryFixedVariant").value,
        tertiaryFixed = animateColorAsState(target.tertiaryFixed, effects, label = "theme_tertiaryFixed").value,
        tertiaryFixedDim = animateColorAsState(target.tertiaryFixedDim, effects, label = "theme_tertiaryFixedDim").value,
        onTertiaryFixed = animateColorAsState(target.onTertiaryFixed, effects, label = "theme_onTertiaryFixed").value,
        onTertiaryFixedVariant = animateColorAsState(target.onTertiaryFixedVariant, effects, label = "theme_onTertiaryFixedVariant").value,
    )
}
