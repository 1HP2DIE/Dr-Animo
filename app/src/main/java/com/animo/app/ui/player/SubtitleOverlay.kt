package com.animo.app.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.animo.app.subtitle.CueAlignment
import com.animo.app.subtitle.SubtitleBackground
import com.animo.app.subtitle.SubtitleStyle
import kotlin.math.roundToInt

/** Base subtitle size as a fraction of the video height; 1080p ≈ 25 px ≈ 22 sp on a phone. */
private const val BASE_HEIGHT_FRACTION = 0.055f

/**
 * Where subtitles sit while the transport controls are on screen: just above the seek bar and
 * below the centred play/pause row, so neither covers the other.
 */
private val ControlsClearance = 100.dp

/**
 * The only subtitle renderer in the app: Media3's built-in `SubtitleView` is hidden, because it
 * accepts one fixed style and would ignore half of these options.
 *
 * [lines] are already-resolved visible cues — either the container track's current cues or the
 * file cues that cover the playback clock. An empty list means "nothing to draw" and this
 * composable draws nothing, so the player can call it unconditionally.
 */
@Composable
fun SubtitleOverlay(
    lines: List<String>,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
    liftForControls: Boolean = false,
) {
    if (lines.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val fontSize = (maxHeight.value * BASE_HEIGHT_FRACTION * style.sizeScale).sp
        val sideMargin = maxWidth * (style.horizontalMarginPercent / 100f)
        val resting = maxHeight * (style.bottomPaddingPercent / 100f)
        val bottom by animateDpAsState(
            targetValue = if (liftForControls) maxOf(resting, ControlsClearance) else resting,
            label = "subtitle-lift",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = bottom),
            contentAlignment = Alignment.Center,
        ) {
            val bedded = style.background != SubtitleBackground.NONE
            Box(
                modifier = Modifier
                    .then(
                        if (style.background == SubtitleBackground.BAR) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.wrapContentWidth()
                        },
                    )
                    .clip(if (style.background == SubtitleBackground.BOX) RoundedCornerShape(10.dp) else RoundedCornerShape(0.dp))
                    .background(
                        if (bedded) {
                            Color(0xFF000000).copy(alpha = style.backgroundOpacity)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(
                        horizontal = if (bedded) sideMargin / 2 + 14.dp else sideMargin,
                        vertical = if (bedded) 8.dp else 0.dp,
                    ),
            ) {
                SubtitleTextBlock(
                    lines = lines,
                    style = style,
                    fontSize = fontSize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Text + outline + shadow for one block of cue lines, with no positioning logic.
 *
 * The outline is a second pass of the same string in `Stroke` draw style rather than a platform
 * shadow/glow: it is the only way to get a uniform ring on every glyph, and layout is identical
 * between the two passes because `drawStyle` never changes measurement.
 */
@Composable
internal fun SubtitleTextBlock(
    lines: List<String>,
    style: SubtitleStyle,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val visible = lines.filter { it.isNotBlank() }
    if (visible.isEmpty()) return

    val density = LocalDensity.current
    val strokeWidth = with(density) { style.outlineWidth.dp.toPx() }
    val raw = visible.joinToString("\n")
    val text = if (style.uppercase) raw.uppercase() else raw

    val textAlign = when (style.alignment) {
        CueAlignment.START -> TextAlign.Start
        CueAlignment.CENTER -> TextAlign.Center
        CueAlignment.END -> TextAlign.End
    }

    val base = TextStyle(
        fontSize = fontSize,
        fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Medium,
        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = textAlign,
        lineHeight = fontSize * style.lineHeight,
        letterSpacing = style.letterSpacing.em,
        shadow = if (style.shadow) {
            Shadow(
                color = Color(0xC0000000),
                offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
                blurRadius = 6f,
            )
        } else {
            null
        },
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        if (strokeWidth >= 0.7f) {
            Text(
                text = text,
                style = base.copy(
                    color = Color(style.outlineColor),
                    drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round, cap = StrokeCap.Round),
                ),
                maxLines = style.maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = text,
            style = base.copy(color = Color(style.textColor)),
            maxLines = style.maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A miniature of the real thing for the settings pane: same renderer, same style, fixed size, on a
 * video-ish background. Keeping it as a real call to [SubtitleTextBlock] is the point — a preview
 * drawn with different code is a preview that lies.
 */
@Composable
internal fun SubtitlePreviewCard(
    style: SubtitleStyle,
    sample: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF233034), Color(0xFF0B0F10)),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.Bottom) {
            Text(
                text = sample.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0x66FFFFFF),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            SubtitleTextBlock(
                lines = listOf(sample),
                style = style.copy(sizeScale = 1f, maxLines = 2),
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** `1.25×` without a locale-dependent comma and without trailing zeros. */
internal fun formatScale(value: Float): String {
    val negative = value < 0f
    val hundredths = (kotlin.math.abs(value) * 100).roundToInt()
    val whole = hundredths / 100
    val rest = hundredths % 100
    val body = when {
        rest == 0 -> "$whole"
        rest % 10 == 0 -> "$whole.${rest / 10}"
        else -> "$whole.${rest.toString().padStart(2, '0')}"
    }
    return if (negative) "-$body" else body
}

/** Percentage label for a slider that works in `0..1` or `0..100` fractions. */
internal fun formatPercent(value: Float): Int = value.roundToInt()
