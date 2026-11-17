package com.animo.app.ui.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.animo.app.ui.common.formatClock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the gesture HUD is currently showing. One value at a time, cleared on release.
 */
sealed interface PlayerHud {
    data class Seek(val targetMs: Long, val stepSeconds: Int) : PlayerHud
    data class Volume(val fraction: Float) : PlayerHud
    data class Brightness(val fraction: Float) : PlayerHud

    /** Double-tap streak: drawn on the side that was tapped, not in the centre. */
    data class TapSeek(val direction: Int, val seconds: Int) : PlayerHud

    /** One-line confirmation for a mode change (fit, lock). */
    data class Message(val text: String, val icon: ImageVector) : PlayerHud
}

@Stable
class PlayerHudState {
    var current: PlayerHud? by mutableStateOf(null)
        private set

    fun show(hud: PlayerHud?) {
        current = hud
    }
}

@Composable
fun rememberPlayerHudState(): PlayerHudState = remember { PlayerHudState() }

private const val LONG_PRESS_MS = 320L

/** Tick length while holding: fast enough to feel continuous, slow enough to read the number. */
private const val SEEK_TICK_MS = 220L

/** The step ladder: 1s, 2s, 3s, 4s, then a constant 5s per tick. */
private const val MAX_STEP_SECONDS = 5

private const val DOUBLE_TAP_WINDOW_MS = 260L

/** After a double tap, every further tap on the same side inside this window adds another 10 s. */
private const val TAP_STREAK_WINDOW_MS = 650L

/** Fingers must spread/close this far before a pinch counts — a resting thumb is not a pinch. */
private const val PINCH_IN_RATIO = 0.82f
private const val PINCH_OUT_RATIO = 1.22f

private enum class GestureMode { UNDECIDED, SEEK, VOLUME, BRIGHTNESS, PINCH }

/**
 * Gesture surface for the video.
 *
 * | gesture | effect |
 * |---|---|
 * | tap | show/hide the chrome (deferred one double-tap window so a double tap never flashes it) |
 * | double tap | ±10 s from the half you tapped |
 * | long press (either half) | accelerated seek: 1,2,3,4 then 5 s per tick, held indefinitely |
 * | vertical drag, right half | media volume |
 * | vertical drag, left half | screen brightness (window override, released with the player) |
 *
 * Written as one `awaitEachGesture` instead of stacking `detectTapGestures` + `detectDragGestures`:
 * those two compete for the same pointer and the loser still sees a tap, which is how you end up
 * seeking while the user is changing the volume.
 */
@Composable
fun Modifier.playerGestures(
    surfaceState: PlayerSurfaceState,
    hud: PlayerHudState,
    onToggleControls: () -> Unit,
    onPinch: (zoomIn: Boolean) -> Unit = {},
): Modifier {
    val context = LocalContext.current
    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var lastTapAt by remember { mutableStateOf(0L) }
    var streakSide by remember { mutableStateOf(0) }
    var streakSeconds by remember { mutableStateOf(0) }
    var streakUntil by remember { mutableStateOf(0L) }

    // Brightness is an override on the window; leaving the player must hand it back to the system.
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val attrs = window.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
    }

    return this.pointerInput(surfaceState, audio, activity) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val leftHalf = down.position.x < width / 2f
            val slop = viewConfiguration.touchSlop

            var mode = GestureMode.UNDECIDED
            var dragStartY = down.position.y
            var startFraction = 0f
            var seekJob: kotlinx.coroutines.Job? = null
            var pinchStart = 0f
            var pinchFired = false

            /**
             * The hold is timed by a coroutine, not by the event loop: a finger that does not move
             * produces no pointer events at all, so polling for "has the timeout passed?" inside
             * `awaitPointerEvent` would only notice after the finger lifted — which is exactly why
             * the first version never started seeking.
             */
            val holdTimer = scope.launch {
                delay(LONG_PRESS_MS)
                if (mode == GestureMode.UNDECIDED) {
                    mode = GestureMode.SEEK
                    seekJob = launchAcceleratedSeek(
                        scope = scope,
                        surfaceState = surfaceState,
                        hud = hud,
                        direction = if (leftHalf) -1 else 1,
                    )
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2 && (mode == GestureMode.UNDECIDED || mode == GestureMode.PINCH)) {
                    holdTimer.cancel()
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (mode != GestureMode.PINCH) {
                        mode = GestureMode.PINCH
                        pinchStart = distance.coerceAtLeast(1f)
                    } else if (!pinchFired) {
                        val ratio = distance / pinchStart
                        if (ratio >= PINCH_OUT_RATIO) {
                            pinchFired = true
                            onPinch(true)
                        } else if (ratio <= PINCH_IN_RATIO) {
                            pinchFired = true
                            onPinch(false)
                        }
                    }
                    pressed.forEach { it.consume() }
                    continue
                }

                if (mode == GestureMode.UNDECIDED &&
                    abs(change.position.y - down.position.y) > slop
                ) {
                    holdTimer.cancel()
                    if (leftHalf) {
                        mode = GestureMode.BRIGHTNESS
                        startFraction = currentBrightness(context, activity)
                    } else {
                        mode = GestureMode.VOLUME
                        startFraction = currentVolumeFraction(audio)
                    }
                    dragStartY = down.position.y
                }

                when (mode) {
                    GestureMode.VOLUME -> {
                        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                        val fraction = (
                            startFraction - verticalDelta(dragStartY, change.position.y, height)
                            ).coerceIn(0f, 1f)
                        if (max > 0) {
                            audio?.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (fraction * max).roundToInt().coerceIn(0, max),
                                0,
                            )
                        }
                        hud.show(PlayerHud.Volume(fraction))
                    }

                    GestureMode.BRIGHTNESS -> {
                        val fraction = (
                            startFraction - verticalDelta(dragStartY, change.position.y, height)
                            ).coerceIn(0.05f, 1f)
                        applyBrightness(activity, fraction)
                        hud.show(PlayerHud.Brightness(fraction))
                    }

                    else -> Unit
                }
                change.consume()
            }

            holdTimer.cancel()
            seekJob?.cancel()

            val released = mode == GestureMode.UNDECIDED
            if (released) {
                val now = System.currentTimeMillis()
                val side = if (leftHalf) -1 else 1
                val inStreak = now <= streakUntil && side == streakSide
                if (inStreak || now - lastTapAt <= DOUBLE_TAP_WINDOW_MS) {
                    // Double tap, or one more tap in a running streak: each adds 10 s on that side.
                    lastTapAt = 0L
                    streakSeconds = if (inStreak) streakSeconds + (SKIP_MS / 1_000L).toInt() else (SKIP_MS / 1_000L).toInt()
                    streakSide = side
                    streakUntil = now + TAP_STREAK_WINDOW_MS
                    surfaceState.skipBy(side * SKIP_MS)
                    hud.show(PlayerHud.TapSeek(direction = side, seconds = streakSeconds))
                    val stamp = streakUntil
                    scope.launch {
                        delay(TAP_STREAK_WINDOW_MS)
                        if (streakUntil == stamp && hud.current is PlayerHud.TapSeek) hud.show(null)
                    }
                } else {
                    lastTapAt = now
                    // Deferred one double-tap window: toggling at once made a double tap flash
                    // the chrome before the seek landed.
                    scope.launch {
                        delay(DOUBLE_TAP_WINDOW_MS)
                        if (lastTapAt == now) onToggleControls()
                    }
                }
            }

            // Tap-seek and mode messages time themselves out; everything else ends with the finger.
            if (hud.current !is PlayerHud.TapSeek && hud.current !is PlayerHud.Message) hud.show(null)
        }
    }
}

/** 1s, 2s, 3s, 4s, then a constant 5s per tick until cancelled. */
private fun launchAcceleratedSeek(
    scope: kotlinx.coroutines.CoroutineScope,
    surfaceState: PlayerSurfaceState,
    hud: PlayerHudState,
    direction: Int,
): kotlinx.coroutines.Job = scope.launch {
    var target = surfaceState.positionMs
    var tick = 0
    while (isActive) {
        val step = (tick + 1).coerceAtMost(MAX_STEP_SECONDS)
        target = (target + direction * step * 1_000L).coerceAtLeast(0L)
        surfaceState.seekTo(target)
        hud.show(PlayerHud.Seek(targetMs = target, stepSeconds = direction * step))
        tick++
        delay(SEEK_TICK_MS)
    }
}

/** Negative when the finger moved up, so callers add it to the starting fraction. */
private fun verticalDelta(startY: Float, currentY: Float, height: Float): Float =
    if (height <= 0f) 0f else (currentY - startY) / height

private fun currentVolumeFraction(audio: AudioManager?): Float {
    val manager = audio ?: return 0f
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun currentBrightness(context: Context, activity: android.app.Activity?): Float {
    val override = activity?.window?.attributes?.screenBrightness ?: -1f
    if (override >= 0f) return override
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)
}

private fun applyBrightness(activity: android.app.Activity?, fraction: Float) {
    val window = activity?.window ?: return
    val attrs = window.attributes
    attrs.screenBrightness = fraction
    window.attributes = attrs
}

// ---------------------------------------------------------------- HUD

/** Small centred pill: what the gesture is doing right now, and by how much. */
@Composable
fun PlayerGestureHud(hud: PlayerHud?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = hud != null && hud !is PlayerHud.TapSeek,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val current = hud ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xCC0D1113))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (current) {
                is PlayerHud.Seek -> {
                    Icon(
                        imageVector = if (current.stepSeconds > 0) {
                            Icons.Rounded.FastForward
                        } else {
                            Icons.Rounded.FastRewind
                        },
                        contentDescription = null,
                        tint = Color(0xFFF3F7F6),
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = formatClock(current.targetMs),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFFF3F7F6),
                        )
                        Text(
                            text = "+${current.stepSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9BF2E6),
                        )
                    }
                }

                is PlayerHud.Volume -> {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFFF3F7F6),
                        modifier = Modifier.size(20.dp),
                    )
                    LevelBar(fraction = current.fraction)
                }

                is PlayerHud.Brightness -> {
                    Icon(
                        imageVector = Icons.Rounded.Brightness6,
                        contentDescription = null,
                        tint = Color(0xFFF3F7F6),
                        modifier = Modifier.size(20.dp),
                    )
                    LevelBar(fraction = current.fraction)
                }

                is PlayerHud.Message -> {
                    Icon(
                        imageVector = current.icon,
                        contentDescription = null,
                        tint = PlayerChrome.accentSoft,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = current.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = PlayerChrome.foreground,
                    )
                }

                // Drawn on its own side by TapSeekIndicator.
                is PlayerHud.TapSeek -> Unit
            }
        }
    }
}

@Composable
private fun LevelBar(fraction: Float) {
    Box(
        Modifier
            .width(120.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(Color(0x55FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(120.dp * fraction.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(Color(0xFF2FD6C0)),
        )
    }
}

/** Shared with the player chrome: 10 s is the double-tap and the base skip. */
private const val SKIP_MS = 10_000L
