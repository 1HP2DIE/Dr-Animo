package com.animo.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Dark-first palette. Deep charcoal, one teal accent, no pure black / pure white on surfaces.
internal val CharcoalBackground = Color(0xFF0D1113)
internal val CharcoalSurfaceLow = Color(0xFF101618)
internal val CharcoalSurface = Color(0xFF141A1D)
internal val CharcoalSurfaceHigh = Color(0xFF1B2327)
internal val CharcoalSurfaceHighest = Color(0xFF232D31)

internal val TealAccent = Color(0xFF2FD6C0)
internal val TealDeep = Color(0xFF14897C)
internal val TealSoft = Color(0xFF9BF2E6)
internal val OnTeal = Color(0xFF04211D)

internal val AmberAccent = Color(0xFFE7B25F)

internal val TextPrimary = Color(0xFFE8EEED)
internal val TextSecondary = Color(0xFF9EACAA)
internal val TextTertiary = Color(0xFF6C7A79)

internal val OutlineStrong = Color(0xFF2C373B)
internal val DangerRed = Color(0xFFFF7B72)
internal val OnDanger = Color(0xFF2B0A07)

// Poster / backdrop placeholders and scrims.
internal val PosterGradientTop = Color(0xFF243136)
internal val PosterGradientBottom = Color(0xFF141A1D)
internal val ScrimTransparent = Color(0x000D1113)
internal val ScrimHeavy = Color(0xE60D1113)
internal val VideoBlack = Color(0xFF000000)

/** Fallback poster fill: same gradient everywhere so a missing poster never looks like a bug. */
internal val PosterPlaceholderBrush: Brush
    get() = Brush.linearGradient(listOf(PosterGradientTop, PosterGradientBottom))

/** Bottom-up scrim that keeps a 2-line title legible on any poster. */
internal val PosterTitleScrim: Brush
    get() = Brush.verticalGradient(
        0f to ScrimTransparent,
        0.45f to Color(0x660D1113),
        1f to Color(0xF20D1113),
    )

/** Full-bleed backdrop scrim: darken the top for the app bar, sink the bottom into the page. */
internal val BackdropScrim: Brush
    get() = Brush.verticalGradient(
        0f to Color(0xCC0D1113),
        0.35f to Color(0x730D1113),
        1f to CharcoalBackground,
    )

/** Player controls scrim: only the top and bottom edges darken, the frame stays readable. */
internal val PlayerTopScrim: Brush
    get() = Brush.verticalGradient(
        0f to Color(0xCC000000),
        1f to Color(0x00000000),
    )

internal val PlayerBottomScrim: Brush
    get() = Brush.verticalGradient(
        0f to Color(0x00000000),
        0.55f to Color(0x80000000),
        1f to Color(0xE6000000),
    )

/**
 * Hero banner scrim. The Dr. Animo banner is artwork, not a theme surface, so its overlay stays
 * dark in both themes — the copy on top is always light (see [AnimoPalette.onHero]).
 */
internal val HeroScrim: Brush
    get() = Brush.verticalGradient(
        0f to Color(0x1A0D1113),
        0.42f to Color(0x660D1113),
        1f to Color(0xF00D1113),
    )

// Light scheme fallbacks (the app is dark-first; these only matter when a user forces light).
internal val LightBackground = Color(0xFFF4F6F5)
internal val LightSurface = Color(0xFFFBFCFB)
internal val LightSurfaceLow = Color(0xFFF7F9F8)
internal val LightSurfaceContainer = Color(0xFFEDF2F0)
internal val LightSurfaceHigh = Color(0xFFE8EDEC)
internal val LightSurfaceHighest = Color(0xFFDDE4E3)
internal val LightTextPrimary = Color(0xFF11191A)
internal val LightTextSecondary = Color(0xFF4C5A59)
internal val LightOutline = Color(0xFFC3CECC)
internal val LightAccent = Color(0xFF10786E)
