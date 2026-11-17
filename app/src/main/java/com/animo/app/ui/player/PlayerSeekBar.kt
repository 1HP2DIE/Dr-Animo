package com.animo.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.animo.app.ui.common.formatClock
import kotlin.math.roundToInt

/** A named stretch of the episode — the intro or the outro — drawn as its own chapter. */
@Immutable
data class SeekChapter(val range: LongRange, val label: String)

/**
 * Seek bar with a buffered channel, chapter gaps and a scrub bubble, drawn by hand: Material's
 * Slider cannot show a third progress channel, and it reserves far more vertical space than the
 * player overlay wants.
 *
 * - Idle it is a 3dp hairline; under the finger it grows to 6dp so you can see what you are moving.
 * - Intro/outro boundaries are cut out of the track (2dp gaps), the way chapters read everywhere
 *   else, so "where does the opening end" is visible before you ever drag.
 * - While dragging, a bubble above the thumb shows the target time and the chapter it lands in.
 *
 * [onScrub] fires continuously while dragging so the caller can update the clock label; [onSeek]
 * fires once on release or on a tap.
 */
@Composable
fun PlayerSeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<SeekChapter> = emptyList(),
    preview: SeekPreviewState? = null,
    trackColor: Color = PlayerChrome.track,
    bufferedColor: Color = PlayerChrome.buffered,
    progressColor: Color = PlayerChrome.accent,
) {
    val enabled = durationMs > 0L
    val safeDuration = durationMs.coerceAtLeast(1L)

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val dragging = dragFraction != null

    val grow by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "seek-grow",
    )

    val density = LocalDensity.current
    var barWidthPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .onSizeChanged { barWidthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * safeDuration).toLong())
                        true
                    }
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val tapped = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((tapped * safeDuration).toLong())
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val start = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = start
                        onScrub((start * safeDuration).toLong())
                    },
                    onDragEnd = {
                        val released = dragFraction
                        dragFraction = null
                        if (released != null) onSeek((released * safeDuration).toLong())
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val current = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = current
                        onScrub((current * safeDuration).toLong())
                    },
                )
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen so the chapter gaps can punch real holes through every channel.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val trackHeight = (3.dp + 3.dp * grow).toPx()
            val thumbRadius = (6.dp + 2.dp * grow).toPx()
            val centerY = size.height / 2f
            val inset = 8.dp.toPx()
            val usableWidth = (size.width - inset * 2f).coerceAtLeast(1f)
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val top = centerY - trackHeight / 2f

            fun channel(color: Color, to: Float) {
                if (to <= 0f) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, top),
                    size = Size(usableWidth * to, trackHeight),
                    cornerRadius = corner,
                )
            }
            channel(trackColor, 1f)
            channel(bufferedColor, bufferedFraction)
            channel(progressColor, fraction)

            // Chapter gaps: one cut at each boundary that is not the very start or end.
            if (enabled && chapters.isNotEmpty()) {
                val gap = 2.dp.toPx()
                chapters
                    .flatMap { listOf(it.range.first, it.range.last) }
                    .filter { it in 1 until safeDuration }
                    .forEach { boundary ->
                        val x = inset + usableWidth * (boundary.toFloat() / safeDuration)
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x - gap / 2f, top - 1f),
                            size = Size(gap, trackHeight + 2f),
                            blendMode = BlendMode.Clear,
                        )
                    }
            }

            if (enabled) {
                drawCircle(
                    color = progressColor,
                    radius = thumbRadius,
                    center = Offset(inset + usableWidth * fraction, centerY),
                )
            }
        }

        // Scrub bubble: time, plus the chapter name when the target is inside one.
        val dragAt = dragFraction
        if (dragAt != null && barWidthPx > 0) {
            val targetMs = (dragAt * safeDuration).toLong()
            val chapter = chapters.firstOrNull { targetMs in it.range }
            val insetPx = with(density) { 8.dp.toPx() }
            val thumbX = insetPx + (barWidthPx - insetPx * 2f) * dragAt
            val x = (thumbX - bubbleWidthPx / 2f)
                .coerceIn(0f, (barWidthPx - bubbleWidthPx).toFloat().coerceAtLeast(0f))
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(x.roundToInt(), -(bubbleHeightPx + with(density) { 2.dp.roundToPx() })) }
                    .onSizeChanged {
                        bubbleWidthPx = it.width
                        bubbleHeightPx = it.height
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(PlayerChrome.panel)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The frame you would land on, when the source publishes preview sprites.
                preview?.frameAt(targetMs)?.let { (sprite, cell) ->
                    Canvas(
                        Modifier
                            .padding(bottom = 4.dp)
                            .size(width = 160.dp, height = 90.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    ) {
                        drawImage(
                            image = sprite,
                            srcOffset = IntOffset(cell.x, cell.y),
                            srcSize = IntSize(cell.width, cell.height),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        )
                    }
                }
                Text(
                    text = formatClock(targetMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = PlayerChrome.foreground,
                )
                if (chapter != null) {
                    Text(
                        text = chapter.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerChrome.accentSoft,
                    )
                }
            }
        }
    }
}
