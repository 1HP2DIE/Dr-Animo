package com.animo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Whether the active scheme is the dark one. Needed by artwork placeholders: the poster fill and its
 * initials are chosen per theme, while everything drawn *on* a real poster stays theme-independent.
 */
internal val LocalIsDarkTheme = staticCompositionLocalOf { true }

internal val AnimoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * Palette pieces the Material colour roles cannot express (scrims, video black, poster fills).
 * Everything else goes through [MaterialTheme.colorScheme] so a future light theme keeps working.
 */
object AnimoPalette {
    val accent: Color get() = TealAccent
    val onAccent: Color get() = OnTeal
    val surface: Color get() = CharcoalSurface
    val surfaceHigh: Color get() = CharcoalSurfaceHigh
    val surfaceHighest: Color get() = CharcoalSurfaceHighest
    val textSecondary: Color get() = TextSecondary
    val textTertiary: Color get() = TextTertiary
    val outline: Color get() = OutlineStrong
    val amber: Color get() = AmberAccent
    val danger: Color get() = DangerRed
    val videoBlack: Color get() = VideoBlack

    /**
     * Copy that sits on the mascot artwork (home hero). The banner is always dark, so this pair
     * must not follow the theme — that is what makes light mode legible there.
     */
    val onHero: Color get() = TextPrimary
    val onHeroSecondary: Color get() = TextSecondary

    /** Artwork copy — theme-independent by design (see [OnPoster]). */
    val onPoster: Color get() = OnPoster
    val onPosterSecondary: Color get() = OnPosterSecondary
    val posterBadgeBg: Color get() = PosterBadgeBg
    val posterBadgeFg: Color get() = PosterBadgeFg

    val posterPlaceholder: Brush get() = PosterPlaceholderBrush
    val posterScrim: Brush get() = PosterTitleScrim
    val backdropTitleScrim: Brush get() = BackdropTitleScrim
    val backdropScrim: Brush get() = BackdropScrim
    val heroScrim: Brush get() = HeroScrim
    val playerTopScrim: Brush get() = PlayerTopScrim
    val playerBottomScrim: Brush get() = PlayerBottomScrim
}

private val AnimoDarkScheme = darkColorScheme(
    primary = TealAccent,
    onPrimary = OnTeal,
    primaryContainer = TealDeep,
    onPrimaryContainer = TealSoft,
    inversePrimary = TealDeep,
    secondary = TealSoft,
    onSecondary = OnTeal,
    secondaryContainer = CharcoalSurfaceHigh,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberAccent,
    onTertiary = OnTeal,
    tertiaryContainer = CharcoalSurfaceHigh,
    onTertiaryContainer = TextPrimary,
    background = CharcoalBackground,
    onBackground = TextPrimary,
    surface = CharcoalBackground,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceTint = TealAccent,
    surfaceBright = CharcoalSurfaceHighest,
    surfaceDim = CharcoalBackground,
    surfaceContainerLowest = CharcoalBackground,
    surfaceContainerLow = CharcoalSurfaceLow,
    surfaceContainer = CharcoalSurface,
    surfaceContainerHigh = CharcoalSurfaceHigh,
    surfaceContainerHighest = CharcoalSurfaceHighest,
    inverseSurface = TextPrimary,
    inverseOnSurface = CharcoalBackground,
    error = DangerRed,
    onError = OnDanger,
    errorContainer = CharcoalSurfaceHigh,
    onErrorContainer = DangerRed,
    outline = OutlineStrong,
    outlineVariant = Color(0xFF1E2629),
    scrim = Color(0xFF000000),
)

private val AnimoLightScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightOnAccentContainer,
    inversePrimary = LightAccentContainer,
    secondary = Color(0xFF41514E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = LightTextPrimary,
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2B0),
    onTertiaryContainer = Color(0xFF2A1800),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightTextSecondary,
    surfaceTint = LightAccent,
    surfaceBright = LightSurface,
    surfaceDim = LightSurfaceHighest,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    inverseSurface = LightTextPrimary,
    inverseOnSurface = LightSurface,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7DAD7),
    onErrorContainer = Color(0xFF410E0B),
    outline = LightOutline,
    outlineVariant = Color(0xFFEAE6DE),
    scrim = Color(0xFF000000),
)

@Composable
fun AnimoTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) AnimoDarkScheme else AnimoLightScheme,
            typography = AnimoTypography,
            shapes = AnimoShapes,
            content = content,
        )
    }
}

/**
 * Theme wrapper that follows the persisted [ThemePreference] (Dark / Light / System).
 *
 * `MainActivity` still installs the base [AnimoTheme]; this second layer is what makes the user's
 * choice win without the activity having to observe DataStore.
 */
@Composable
fun AnimoAppTheme(
    preference: ThemePreference = rememberThemePreference(),
    content: @Composable () -> Unit,
) {
    val dark = when (preference.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    AnimoTheme(darkTheme = dark, content = content)
}
