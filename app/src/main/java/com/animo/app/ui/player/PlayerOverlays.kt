package com.animo.app.ui.player

import android.content.Context
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.Episode
import com.animo.app.ui.common.displayTitle
import kotlinx.coroutines.delay

private val FloatingShape = RoundedCornerShape(12.dp)

// ---------------------------------------------------------------- tap-seek ripple

/**
 * Double-tap feedback drawn on the half of the frame that was tapped: a soft half-disc with the
 * running total. Centred feedback made a left double-tap look identical to a right one.
 */
@Composable
fun BoxScope.TapSeekIndicator(hud: PlayerHud?) {
    val tap = hud as? PlayerHud.TapSeek
    var last by remember { mutableStateOf<PlayerHud.TapSeek?>(null) }
    if (tap != null) last = tap
    val shown = last ?: return
    val forward = shown.direction > 0

    AnimatedVisibility(
        visible = tap != null,
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(220)),
        modifier = Modifier
            .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.36f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(
                    if (forward) {
                        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    } else {
                        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    },
                )
                .background(PlayerChrome.tapRipple),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                    contentDescription = null,
                    tint = PlayerChrome.foreground,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.ui_player_ctl_tap_seek, shown.seconds),
                    style = MaterialTheme.typography.labelLarge,
                    color = PlayerChrome.foreground,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- skip intro / outro

/**
 * "Skip intro" — shown while the playhead is inside a marked chapter, whether or not the rest of
 * the chrome is up, because that is exactly the moment people reach for it.
 */
@Composable
fun SkipChapterButton(
    visible: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 3 },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 3 },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(FloatingShape)
                .background(PlayerChrome.panel)
                .border(1.dp, PlayerChrome.panelBorder, FloatingShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = PlayerChrome.foreground,
            )
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = null,
                tint = PlayerChrome.foreground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- up next

/**
 * The end-of-episode card. When autoplay is on, the "Play now" button fills from left to right as
 * the countdown runs, so the timer and the action are the same object rather than two.
 *
 * @param countdownFraction 0 → 1 as the countdown elapses; `null` when autoplay is off.
 */
@Composable
fun UpNextCard(
    visible: Boolean,
    next: Episode?,
    secondsLeft: Int?,
    countdownFraction: Float?,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(next) }
    if (next != null) shown = next
    val episode = shown ?: return

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInHorizontally(tween(260)) { it / 4 },
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .width(296.dp)
                .clip(FloatingShape)
                .background(PlayerChrome.panel)
                .border(1.dp, PlayerChrome.panelBorder, FloatingShape)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ui_player_ctl_up_next).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = PlayerChrome.foregroundMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_player_ctl_dismiss),
                        tint = PlayerChrome.foregroundMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.ui_player_episode_label,
                    episode.number,
                    episode.displayTitle(),
                ),
                style = MaterialTheme.typography.titleSmall,
                color = PlayerChrome.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (countdownFraction == null) PlayerChrome.accent else PlayerChrome.controlFill)
                    .clickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (countdownFraction != null) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(countdownFraction.coerceIn(0f, 1f))
                            .background(PlayerChrome.accent),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Text sits on the accent for most of the countdown; dark ink reads on both.
                    val ink = if (countdownFraction == null || countdownFraction > 0.5f) {
                        PlayerChrome.onAccent
                    } else {
                        PlayerChrome.foreground
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (secondsLeft != null) {
                            stringResource(R.string.ui_player_ctl_next_in, secondsLeft)
                        } else {
                            stringResource(R.string.ui_player_ctl_play_now)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- lock

/**
 * Covers the whole frame while the controls are locked, so a pocket or a toddler cannot seek.
 * Any tap reveals the unlock button for a few seconds; nothing else gets through.
 */
@Composable
fun LockedOverlay(
    onUnlock: () -> Unit,
    revealToken: Int,
    modifier: Modifier = Modifier,
) {
    var hintVisible by remember { mutableStateOf(true) }
    var taps by remember { mutableStateOf(0) }
    LaunchedEffect(taps, revealToken) {
        hintVisible = true
        delay(2_500L)
        hintVisible = false
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { taps++ },
            ),
    ) {
        AnimatedVisibility(
            visible = hintVisible,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(PlayerChrome.panel)
                        .border(1.dp, PlayerChrome.panelBorder, CircleShape)
                        .clickable(role = Role.Button, onClick = onUnlock),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LockOpen,
                        contentDescription = stringResource(R.string.ui_player_ctl_unlock),
                        tint = PlayerChrome.foreground,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ui_player_ctl_unlock),
                    style = MaterialTheme.typography.labelMedium,
                    color = PlayerChrome.foregroundMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- status

/**
 * Wall clock and battery. The player hides the status bar, and an episode-binge is exactly when
 * you need to know it is 2 a.m. and the phone is at 9 %.
 */
@Composable
fun PlayerStatusReadout(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(context) {
        val format = android.text.format.DateFormat.getTimeFormat(context)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        while (true) {
            clock = format.format(java.util.Date())
            battery = batteryManager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            delay(15_000L)
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = clock,
            style = MaterialTheme.typography.labelMedium,
            color = PlayerChrome.foregroundMuted,
        )
        battery?.let { level ->
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Rounded.BatteryStd,
                contentDescription = null,
                tint = if (level <= 15) MaterialTheme.colorScheme.error else PlayerChrome.foregroundMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "$level%",
                style = MaterialTheme.typography.labelMedium,
                color = PlayerChrome.foregroundMuted,
            )
        }
    }
}
