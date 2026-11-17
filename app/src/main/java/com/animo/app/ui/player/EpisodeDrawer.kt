package com.animo.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.Episode
import com.animo.app.ui.common.BadgeTone
import com.animo.app.ui.common.PillBadge
import com.animo.app.ui.common.displayTitle
import com.animo.app.ui.common.isPlayable
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme

private const val SLIDE_DURATION_MS = 220
private const val SCRIM_DURATION_MS = 180

/** Panel is readability-bound, not density-bound: never wider than a comfortable text column. */
private val PanelMaxWidth = 340.dp

/** On narrow phones the panel must still leave the video visible behind a tappable scrim. */
private const val PANEL_SCREEN_FRACTION = 0.86f

private val PanelCornerRadius = 20.dp

/** Fixed gutter so 1-digit and 3-digit episode numbers keep their titles on the same x. */
private val NumberGutter = 28.dp

private val ProgressLineHeight = 2.dp

/** Same 45% fade the detail episode grid uses for rows without a source. */
private const val UNPLAYABLE_ALPHA = 0.45f

/** Same tint as the detail grid's current episode, so the "you are here" cue is one visual idea. */
private const val CURRENT_ROW_ALPHA = 0.12f

/** Dark enough to hide the controls underneath, light enough to keep the video recognisable. */
private val DrawerScrim = Color(0x8C000000)

/** Lazy items share one composition type: rows are homogeneous, so reuse the same slot. */
private const val EPISODE_CONTENT_TYPE = "episode"

/**
 * Right-anchored episode list for the player.
 *
 * Why a drawer and not the detail grid: a Hindi/Pokémon season can be 100+ episodes, so the player
 * needs a scannable, scrollable list it can open without leaving playback. The header is pinned
 * outside the [LazyColumn] for the same reason — the count and the close affordance must stay
 * reachable no matter how deep the user has scrolled.
 *
 * @param progress `episodeId -> watched fraction (0..1)`. Missing ids mean "no data"; a present
 *   `0f` still renders the empty track, because that is a known state, not an unknown one.
 */
@Composable
fun EpisodeDrawer(
    visible: Boolean,
    episodes: List<Episode>,
    currentEpisodeId: Long,
    progress: Map<Long, Float>,
    onSelect: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    // The API returns rows roughly in order, but "roughly" is not a contract — sort here so the
    // gutter always counts up and `index - 1` below is meaningful.
    val ordered = remember(episodes) { episodes.sortedBy { it.number } }
    val listState = rememberLazyListState()
    val scrimInteraction = remember { MutableInteractionSource() }

    // Scroll once per opening. Keyed on the episode too so a re-open that lands on a new episode
    // still re-centres, while a mid-scroll episode change (autoplay next) never yanks the list.
    var alignedForVisibility by remember { mutableStateOf(false) }
    LaunchedEffect(visible, currentEpisodeId) {
        if (!visible) {
            alignedForVisibility = false
            return@LaunchedEffect
        }
        if (alignedForVisibility) return@LaunchedEffect
        val index = ordered.indexOfFirst { it.id == currentEpisodeId }
        if (index >= 0) {
            listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
        alignedForVisibility = true
    }

    // The drawer owns back while it is open; otherwise the player would close underneath it.
    BackHandler(enabled = visible) { onDismiss() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = minOf(PanelMaxWidth, maxWidth * PANEL_SCREEN_FRACTION)

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = SCRIM_DURATION_MS)),
            exit = fadeOut(animationSpec = tween(durationMillis = SCRIM_DURATION_MS)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DrawerScrim)
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(animationSpec = tween(durationMillis = SLIDE_DURATION_MS)) { it },
            exit = slideOutHorizontally(animationSpec = tween(durationMillis = SLIDE_DURATION_MS)) { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = PanelCornerRadius,
                            bottomStart = PanelCornerRadius,
                        ),
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                EpisodeDrawerHeader(
                    count = ordered.size,
                    onClose = onDismiss,
                    modifier = Modifier.statusBarsPadding(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (ordered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ui_player_episodes_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        items(
                            items = ordered,
                            key = { it.id },
                            contentType = { EPISODE_CONTENT_TYPE },
                        ) { episode ->
                            EpisodeDrawerRow(
                                episode = episode,
                                isCurrent = episode.id == currentEpisodeId,
                                watchedFraction = progress[episode.id],
                                onSelect = { onSelect(episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeDrawerHeader(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_player_episodes_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.ui_player_episodes_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ui_player_episodes_close),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EpisodeDrawerRow(
    episode: Episode,
    isCurrent: Boolean,
    watchedFraction: Float?,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val playable = episode.isPlayable()
    val hasSub = !episode.subUrl.isNullOrBlank()
    val hasDub = !episode.dubUrl.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) colors.primary.copy(alpha = CURRENT_ROW_ALPHA) else Color.Transparent)
            .alpha(if (playable) 1f else UNPLAYABLE_ALPHA)
            .clickable(enabled = playable, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = episode.number.toString(),
            modifier = Modifier.width(NumberGutter),
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = episode.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasSub || hasDub) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasSub) {
                        PillBadge(
                            text = stringResource(R.string.label_sub),
                            tone = BadgeTone.Accent,
                        )
                    }
                    if (hasDub) {
                        PillBadge(
                            text = stringResource(R.string.label_dub),
                            tone = BadgeTone.Muted,
                        )
                    }
                }
            }
            if (watchedFraction != null) {
                WatchedLine(fraction = watchedFraction)
            }
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.ui_player_episodes_current),
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 2 dp watched line. Only drawn for episodes the caller actually has progress for, so unknown rows
 * stay compact instead of reserving space for a bar that may never fill.
 */
@Composable
private fun WatchedLine(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(ProgressLineHeight)
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = 0.10f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(safe, 0f..1f) },
    ) {
        if (safe > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(safe)
                    .height(ProgressLineHeight)
                    .background(colors.primary),
            )
        }
    }
}

@Preview(name = "EpisodeDrawer", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 360, heightDp = 640)
@Composable
private fun EpisodeDrawerPreview() {
    val episodes = remember {
        List(8) { index ->
            val number = index + 1
            val id = 700_000L + index
            val playable = index != 7
            Episode(
                id = id,
                animeId = 1458,
                number = number,
                title = if (index % 3 == 0) "" else "Episode title $number",
                subUrl = if (playable) "https://megaplay.buzz/stream/s-2/$id/sub" else null,
                dubUrl = if (playable && index % 2 == 0) "https://megaplay.buzz/stream/s-2/$id/dub" else null,
            )
        }
    }
    AnimoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(AnimoPalette.videoBlack),
        ) {
            EpisodeDrawer(
                visible = true,
                episodes = episodes,
                currentEpisodeId = 700_002L,
                progress = mapOf(700_000L to 0.62f, 700_001L to 0.18f),
                onSelect = {},
                onDismiss = {},
            )
        }
    }
}
