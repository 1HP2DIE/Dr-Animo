package com.animo.app.ui.player

import androidx.compose.ui.graphics.Color
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Colour roles for everything drawn on top of the video.
 *
 * The video is dark in both app themes, so the player chrome never follows the colour scheme: these
 * are fixed roles, named for what they do. Near-white and near-black only — a pure #FFF glyph on a
 * dark frame halates, and the teal accent is reserved for "where you are" (progress, the selected
 * thing, the one primary action on a card).
 */
internal object PlayerChrome {
    val foreground = Color(0xFFF1F5F4)
    val foregroundMuted = Color(0xB8F1F5F4)
    val foregroundFaint = Color(0x80F1F5F4)

    /** Fill behind a round control so it reads on a white frame without becoming a button slab. */
    val controlFill = Color(0x33FFFFFF)
    val controlFillPressed = Color(0x4DFFFFFF)

    /** Floating cards (up next, skip, lock): charcoal, not black, so they read as a surface. */
    val panel = Color(0xE6141A1D)
    val panelBorder = Color(0x1FFFFFFF)

    val accent = Color(0xFF2FD6C0)
    val onAccent = Color(0xFF04211D)
    val accentSoft = Color(0xFF9BF2E6)

    val track = Color(0x40FFFFFF)
    val buffered = Color(0x8CFFFFFF)

    /** Double-tap ripple on the side of the frame you tapped. */
    val tapRipple = Color(0x26FFFFFF)
}

/**
 * How the frame fills the screen. Cycled from the top bar and by pinching the video.
 *
 * Only three of Media3's five modes: "fixed width/height" are layout tools, not viewer choices.
 */
enum class VideoFit(val resizeMode: Int) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ;

    fun next(): VideoFit = entries[(ordinal + 1) % entries.size]
}
