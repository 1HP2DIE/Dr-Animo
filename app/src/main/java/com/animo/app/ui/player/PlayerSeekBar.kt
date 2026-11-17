package com.animo.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Seek bar with a buffered indicator, drawn by hand: Material's Slider cannot show a third
 * progress channel, and it reserves far more vertical space than the player overlay wants.
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
    trackColor: Color = Color(0x4DFFFFFF),
    bufferedColor: Color = Color(0xB3FFFFFF),
    progressColor: Color = Color(0xFF2FD6C0),
) {
    val enabled = durationMs > 0L
    val safeDuration = durationMs.coerceAtLeast(1L)

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
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
        Canvas(Modifier.fillMaxSize()) {
            val trackHeight = 4.dp.toPx()
            val thumbRadius = 5.5.dp.toPx()
            val centerY = size.height / 2f
            val usableWidth = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val top = centerY - trackHeight / 2f

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(thumbRadius, top),
                size = Size(usableWidth, trackHeight),
                cornerRadius = corner,
            )
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(thumbRadius, top),
                    size = Size(usableWidth * bufferedFraction, trackHeight),
                    cornerRadius = corner,
                )
            }
            if (fraction > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(thumbRadius, top),
                    size = Size(usableWidth * fraction, trackHeight),
                    cornerRadius = corner,
                )
            }
            drawCircle(
                color = progressColor,
                radius = if (enabled) thumbRadius else thumbRadius * 0.7f,
                center = Offset(thumbRadius + usableWidth * fraction, centerY),
            )
        }
    }
}
