package com.animo.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.animo.app.R

/**
 * The accent the viewer picked. Each one is tuned twice: a light, slightly desaturated tone that
 * sits on the charcoal ground without vibrating, and a deeper tone that keeps 4.5:1 on white.
 * Neutrals never change with the accent; only the accent roles do.
 */
enum class AccentPalette(
    @StringRes val labelRes: Int,
    val dark: Color,
    val darkDeep: Color,
    val darkSoft: Color,
    val onDark: Color,
    val light: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
) {
    TEAL(R.string.ui_accent_teal, TealAccent, TealDeep, TealSoft, OnTeal, LightAccent, LightAccentContainer, LightOnAccentContainer),
    SAKURA(R.string.ui_accent_sakura, Color(0xFFFF8FB1), Color(0xFFB34D6E), Color(0xFFFFD1DF), Color(0xFF3A0A1A), Color(0xFFB0325F), Color(0xFFFFD9E3), Color(0xFF3E0019)),
    AMBER(R.string.ui_accent_amber, Color(0xFFF2B45A), Color(0xFFA06A14), Color(0xFFFFE0B0), Color(0xFF2A1800), Color(0xFF8A5A00), Color(0xFFFFE2B0), Color(0xFF2A1800)),
    SKY(R.string.ui_accent_sky, Color(0xFF6CB8FF), Color(0xFF2A6FB0), Color(0xFFCDE6FF), Color(0xFF04203A), Color(0xFF1F5FA0), Color(0xFFD3E4FF), Color(0xFF001C38)),
    LIME(R.string.ui_accent_lime, Color(0xFFA6D96A), Color(0xFF5C8A26), Color(0xFFDDF5BF), Color(0xFF142400), Color(0xFF4A6D1C), Color(0xFFD8F0B8), Color(0xFF112000)),
    CORAL(R.string.ui_accent_coral, Color(0xFFFF8A65), Color(0xFFB34A2A), Color(0xFFFFD3C4), Color(0xFF3A1004), Color(0xFFA63D1D), Color(0xFFFFDBD0), Color(0xFF3A0B00)),
    ;

    companion object {
        fun of(name: String?): AccentPalette = entries.firstOrNull { it.name == name } ?: TEAL
    }
}

/**
 * The accent in force right now, readable outside a `MaterialTheme` (player chrome, poster badges).
 * Snapshot state, so any composable that reads it recomposes when the viewer picks another one.
 */
internal object ActiveAccent {
    var palette by mutableStateOf(AccentPalette.TEAL)
}

private val AmoledBackground = Color(0xFF000000)
private val AmoledLow = Color(0xFF07090A)
private val AmoledContainer = Color(0xFF0C1012)
private val AmoledHigh = Color(0xFF151B1E)
private val AmoledHighest = Color(0xFF1D2529)

/** Applies [accent] (and pure black for [amoled]) to one of the two base schemes. */
internal fun ColorScheme.styled(dark: Boolean, accent: AccentPalette, amoled: Boolean): ColorScheme {
    val accented = if (dark) {
        copy(
            primary = accent.dark,
            onPrimary = accent.onDark,
            primaryContainer = accent.darkDeep,
            onPrimaryContainer = accent.darkSoft,
            inversePrimary = accent.darkDeep,
            secondary = accent.darkSoft,
            onSecondary = accent.onDark,
            surfaceTint = accent.dark,
        )
    } else {
        copy(
            primary = accent.light,
            primaryContainer = accent.lightContainer,
            onPrimaryContainer = accent.onLightContainer,
            inversePrimary = accent.lightContainer,
            surfaceTint = accent.light,
        )
    }
    if (!dark || !amoled) return accented
    return accented.copy(
        background = AmoledBackground,
        surface = AmoledBackground,
        surfaceDim = AmoledBackground,
        surfaceContainerLowest = AmoledBackground,
        surfaceContainerLow = AmoledLow,
        surfaceContainer = AmoledContainer,
        surfaceContainerHigh = AmoledHigh,
        surfaceContainerHighest = AmoledHighest,
        surfaceVariant = AmoledHigh,
        surfaceBright = AmoledHighest,
        inverseOnSurface = AmoledBackground,
    )
}

/**
 * Crossfades the roles a theme switch changes, so picking an accent or flipping light/dark eases
 * over ~350 ms instead of snapping the whole screen.
 */
@Composable
internal fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(350)
    @Composable fun Color.anim(label: String) = animateColorAsState(this, spec, label = label).value
    return copy(
        primary = primary.anim("primary"),
        onPrimary = onPrimary.anim("onPrimary"),
        primaryContainer = primaryContainer.anim("primaryContainer"),
        onPrimaryContainer = onPrimaryContainer.anim("onPrimaryContainer"),
        secondary = secondary.anim("secondary"),
        background = background.anim("background"),
        onBackground = onBackground.anim("onBackground"),
        surface = surface.anim("surface"),
        onSurface = onSurface.anim("onSurface"),
        onSurfaceVariant = onSurfaceVariant.anim("onSurfaceVariant"),
        surfaceVariant = surfaceVariant.anim("surfaceVariant"),
        surfaceContainerLow = surfaceContainerLow.anim("surfaceContainerLow"),
        surfaceContainer = surfaceContainer.anim("surfaceContainer"),
        surfaceContainerHigh = surfaceContainerHigh.anim("surfaceContainerHigh"),
        surfaceContainerHighest = surfaceContainerHighest.anim("surfaceContainerHighest"),
        outline = outline.anim("outline"),
        outlineVariant = outlineVariant.anim("outlineVariant"),
    )
}
