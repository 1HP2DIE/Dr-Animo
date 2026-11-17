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

/**
 * Copy drawn on top of artwork. Artwork is dark in **both** themes (a poster scrim is always
 * charcoal), so this pair must never follow the colour scheme — using `onSurface` here put black
 * text on a bright poster the moment the user picked light mode.
 */
internal val OnPoster = Color(0xFFF3F7F6)
internal val OnPosterSecondary = Color(0xCCF3F7F6)

/** Tint pill used for source markers on a poster (Hindi / DUB). */
internal val PosterBadgeBg = Color(0xCC0D1113)
internal val PosterBadgeFg = Color(0xFF9BF2E6)

/** Light-theme poster fill: a missing poster must look like paper, not like a black hole. */
internal val LightPosterGradientTop = Color(0xFFE9E6DE)
internal val LightPosterGradientBottom = Color(0xFFDBD7CD)

/** Fallback poster fill: same gradient everywhere so a missing poster never looks like a bug. */
internal val PosterPlaceholderBrush: Brush
    get() = Brush.linearGradient(listOf(PosterGradientTop, PosterGradientBottom))

/**
 * Bottom-up scrim that keeps a 2-line title legible on any poster. Measured against a white
 * poster: at 0.45 the title was still fighting the artwork, so the ramp starts earlier and lands
 * at 0.96 (not 0.95) for real contrast.
 */
internal val PosterTitleScrim: Brush
    get() = Brush.verticalGradient(
        0f to ScrimTransparent,
        0.34f to Color(0x330D1113),
        0.62f to Color(0x990D1113),
        1f to Color(0xF50D1113),
    )

/** Same idea, tuned for 16:9 backdrops where the title sits in the lower third. */
internal val BackdropTitleScrim: Brush
    get() = Brush.verticalGradient(
        0f to ScrimTransparent,
        0.30f to Color(0x1A0D1113),
        0.60f to Color(0xB30D1113),
        1f to Color(0xF70D1113),
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

// Light scheme. Not a tinted dark theme: a warm paper base with ink text and one deep teal.
// The earlier version used a green-tinted grey family, which is what made the UI look washed out
// and "not colour matched" next to the dark-only hero artwork.
internal val LightBackground = Color(0xFFF5F4F0)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceLow = Color(0xFFFAF9F6)
internal val LightSurfaceContainer = Color(0xFFFFFFFF)
internal val LightSurfaceHigh = Color(0xFFF0EEE8)
internal val LightSurfaceHighest = Color(0xFFE5E2DA)
internal val LightTextPrimary = Color(0xFF12171A)
internal val LightTextSecondary = Color(0xFF5A6462)
internal val LightTextTertiary = Color(0xFF8A9391)
internal val LightOutline = Color(0xFFDBD7CE)
internal val LightAccent = Color(0xFF0B6E63)
internal val LightAccentContainer = Color(0xFFCDEDE6)
internal val LightOnAccentContainer = Color(0xFF00251F)
