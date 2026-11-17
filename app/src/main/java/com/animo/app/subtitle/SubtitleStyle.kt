package com.animo.app.subtitle

import kotlinx.serialization.Serializable

/** What sits behind a subtitle line. */
@Serializable
enum class SubtitleBackground { NONE, BOX, BAR }

/**
 * Everything the viewer can change about how subtitles look.
 *
 * Colours are stored as `0xAARRGGBB` longs rather than Compose `Color` because this value is
 * serialised into DataStore; the UI converts at the edge. Sizes are relative (a multiple of the
 * base size, a percentage of the video height) so one preset looks right on a phone, a tablet and
 * in picture-in-picture without a second set of numbers.
 */
@Serializable
data class SubtitleStyle(
    val textColor: Long = 0xFFFFFFFF,
    val outlineColor: Long = 0xFF000000,
    /** Outline thickness in dp; `0` switches the outline off. */
    val outlineWidth: Float = 2f,
    val shadow: Boolean = true,
    val background: SubtitleBackground = SubtitleBackground.NONE,
    val backgroundOpacity: Float = 0.55f,
    /** Multiplier on the base size (about 5.4% of the video height). */
    val sizeScale: Float = 1f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val uppercase: Boolean = false,
    /** Extra letter spacing in em. `0` is the font's own spacing. */
    val letterSpacing: Float = 0f,
    /** Line height as a multiple of the font size. */
    val lineHeight: Float = 1.24f,
    /** Distance from the bottom edge, as a percentage of the video height. */
    val bottomPaddingPercent: Float = 10f,
    /** Side margin, as a percentage of the video width. */
    val horizontalMarginPercent: Float = 6f,
    val maxLines: Int = 3,
    val alignment: CueAlignment = CueAlignment.CENTER,
)

/** A named look. Built-in presets ship with the app; the rest are the viewer's own. */
@Serializable
data class SubtitlePreset(
    val id: String,
    val name: String,
    val style: SubtitleStyle,
    val builtIn: Boolean = false,
)

/** Slider ranges, kept next to the model so the UI and the clamp can never disagree. */
object SubtitleStyleLimits {
    const val SIZE_MIN = 0.6f
    const val SIZE_MAX = 2.2f
    const val OUTLINE_MIN = 0f
    const val OUTLINE_MAX = 6f
    const val OPACITY_MIN = 0.1f
    const val OPACITY_MAX = 1f
    const val BOTTOM_MIN = 0f
    const val BOTTOM_MAX = 40f
    const val MAX_LINES_MIN = 1
    const val MAX_LINES_MAX = 4
    const val LETTER_SPACING_MIN = -0.02f
    const val LETTER_SPACING_MAX = 0.12f

    /** Clamps a style to the ranges above — a preset from an older build must not break layout. */
    fun sanitize(style: SubtitleStyle): SubtitleStyle = style.copy(
        sizeScale = style.sizeScale.coerceIn(SIZE_MIN, SIZE_MAX),
        outlineWidth = style.outlineWidth.coerceIn(OUTLINE_MIN, OUTLINE_MAX),
        backgroundOpacity = style.backgroundOpacity.coerceIn(OPACITY_MIN, OPACITY_MAX),
        bottomPaddingPercent = style.bottomPaddingPercent.coerceIn(BOTTOM_MIN, BOTTOM_MAX),
        horizontalMarginPercent = style.horizontalMarginPercent.coerceIn(0f, 25f),
        lineHeight = style.lineHeight.coerceIn(0.9f, 2f),
        maxLines = style.maxLines.coerceIn(MAX_LINES_MIN, MAX_LINES_MAX),
        letterSpacing = style.letterSpacing.coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX),
    )
}

/** The looks that ship with the app, plus the factory default. */
object SubtitleStyles {

    /** What a fresh install (and "reset") uses: white, thin outline, no box. */
    val Default: SubtitleStyle = SubtitleStyle()

    val builtInPresets: List<SubtitlePreset> = listOf(
        SubtitlePreset(
            id = "classic",
            name = "Classic",
            style = Default.copy(shadow = false),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "cinema",
            name = "Cinema",
            style = Default.copy(
                outlineWidth = 1.2f,
                shadow = true,
                sizeScale = 0.95f,
                bottomPaddingPercent = 12f,
                lineHeight = 1.3f,
            ),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "boxed",
            name = "Boxed",
            style = Default.copy(
                outlineWidth = 0f,
                shadow = false,
                background = SubtitleBackground.BOX,
                backgroundOpacity = 0.72f,
            ),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "bar",
            name = "Clean bar",
            style = Default.copy(
                outlineWidth = 0f,
                shadow = false,
                background = SubtitleBackground.BAR,
                backgroundOpacity = 0.55f,
                bottomPaddingPercent = 0f,
            ),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "bold",
            name = "Big & bold",
            style = Default.copy(
                sizeScale = 1.35f,
                bold = true,
                outlineWidth = 3f,
                maxLines = 2,
            ),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "minimal",
            name = "Minimal",
            style = Default.copy(
                outlineWidth = 0f,
                shadow = true,
                sizeScale = 0.88f,
                textColor = 0xFFF3F7F6,
                bottomPaddingPercent = 8f,
            ),
            builtIn = true,
        ),
        SubtitlePreset(
            id = "neon",
            name = "Neon",
            style = Default.copy(
                textColor = 0xFF9BF2E6,
                outlineColor = 0xFF04211D,
                outlineWidth = 2.2f,
                shadow = false,
                bold = true,
            ),
            builtIn = true,
        ),
    )
}
