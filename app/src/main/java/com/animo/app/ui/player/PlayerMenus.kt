package com.animo.app.ui.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.player.PlayerController
import com.animo.app.ui.theme.AnimoTheme
import kotlin.math.abs

/** The four panes of the settings sheet; each bottom-bar glyph opens its own. */
enum class PlayerMenuTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    QUALITY(R.string.ui_player_menu_tab_quality, Icons.Rounded.HighQuality),
    AUDIO(R.string.ui_player_menu_tab_audio, Icons.Rounded.GraphicEq),
    SUBTITLE(R.string.ui_player_menu_tab_subtitle, Icons.Rounded.Subtitles),
    SPEED(R.string.ui_player_menu_tab_speed, Icons.Rounded.Speed),
    SLEEP(R.string.ui_player_menu_tab_sleep, Icons.Rounded.Bedtime),
}

private val PanelShapeBottom = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SidePanelShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
internal val CardShape = RoundedCornerShape(16.dp)

/** Cards are 1.5dp of border apart — enough to read as a control, not enough to look boxed in. */
internal val SelectedBorder = 1.5.dp
internal val IdleBorder = 1.dp
private val SidePanelWidth = 400.dp
private val WideLayoutBreakpoint = 600.dp

/**
 * Playback settings — one surface, four panes.
 *
 * Shape follows the screen: a **side sheet** in landscape (the player is always landscape, so this is
 * the normal case) and a bottom sheet on anything narrow. The previous version was a full-width
 * list of radio rows, which read as a settings menu from 2016 and buried the one thing people open
 * it for — the resolution they are currently on.
 *
 * Every pane is card-first: the value is the primary typography, the explanation is secondary, and
 * the selected state is a border + tint rather than a trailing check-mark alone.
 */
@Composable
fun PlayerSettingsSheet(
    tuning: PlayerTuning,
    /** The timer object itself: the pane reads the countdown, so the sheet does not re-time the stage. */
    sleepTimer: SleepTimerState,
    onDismiss: () -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectLanguage: (serverNumber: Int, languageId: String) -> Unit = { _, _ -> },
    onSelectSpeed: (Float) -> Unit,
    onSelectSleep: (Int?) -> Unit,
    subtitle: SubtitlePanelState = SubtitlePanelState(),
    subtitleActions: SubtitlePanelActions = SubtitlePanelActions(),
    initialTab: PlayerMenuTab = PlayerMenuTab.QUALITY,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val scrimInteraction = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xA6000000))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        val wide = maxWidth >= WideLayoutBreakpoint
        val panelModifier = if (wide) {
            Modifier
                .align(Alignment.CenterEnd)
                .width(SidePanelWidth)
                .fillMaxHeight()
                .clip(SidePanelShape)
                .statusBarsPadding()
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.78f)
                .clip(PanelShapeBottom)
                .navigationBarsPadding()
        }

        Column(
            modifier = panelModifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    IdleBorder,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    if (wide) SidePanelShape else PanelShapeBottom,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            PanelHeader(onDismiss = onDismiss, wide = wide)
            TabStrip(selected = tabIndex, onSelect = { tabIndex = it })
            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    val offset = if (forward) 1 else -1
                    (fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 12 * offset })
                        .togetherWith(
                            fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -it / 12 * offset },
                        )
                },
                label = "player-menu-pane",
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (PlayerMenuTab.entries[index]) {
                        PlayerMenuTab.QUALITY -> QualityPane(tuning, onSelectQuality)
                        PlayerMenuTab.AUDIO -> AudioPane(tuning, onSelectAudio, onSelectLanguage)
                        PlayerMenuTab.SUBTITLE -> SubtitlePane(tuning, subtitle, subtitleActions)
                        PlayerMenuTab.SPEED -> SpeedPane(tuning, onSelectSpeed)
                        PlayerMenuTab.SLEEP -> SleepPane(sleepTimer, onSelectSleep)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(onDismiss: () -> Unit, wide: Boolean) {
    Column {
        if (!wide) {
            // Drag-handle affordance: the bottom sheet is the only variant you can gesture.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = if (wide) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ui_player_menu_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ui_player_menu_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TabStrip(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlayerMenuTab.entries.forEachIndexed { index, tab ->
            val active = index == selected
            val background by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
                animationSpec = tween(180),
                label = "tab-bg-$index",
            )
            val content by animateColorAsState(
                targetValue = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180),
                label = "tab-fg-$index",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- quality

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityPane(tuning: PlayerTuning, onSelect: (String) -> Unit) {
    if (!tuning.hasQualities) {
        EmptyNote(stringResource(R.string.ui_player_menu_quality_single))
        return
    }

    val autoSelected = tuning.activeQualityId == PlayerController.AUTO_QUALITY_ID
    SettingCard(
        title = stringResource(R.string.ui_player_menu_auto),
        hint = stringResource(
            if (tuning.autoIsHighest) R.string.ui_player_menu_auto_hint_highest else R.string.ui_player_menu_auto_hint,
        ),
        selected = autoSelected,
        glyph = Icons.Rounded.HighQuality,
        highlighted = true,
        onClick = { onSelect(PlayerController.AUTO_QUALITY_ID) },
    )

    SectionLabel(stringResource(R.string.ui_player_menu_tab_quality))
    val ordered = tuning.qualities.sortedByDescending { it.height }
    ordered.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { quality ->
                SettingCard(
                    title = quality.label.ifBlank { "${quality.height}p" },
                    hint = bitrateHint(quality.bitrate),
                    selected = quality.id == tuning.activeQualityId,
                    onClick = { onSelect(quality.id) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun bitrateHint(bitrate: Int): String? =
    if (bitrate <= 0) {
        null
    } else {
        stringResource(R.string.ui_player_menu_quality_hint_bitrate_only, formatMbps(bitrate))
    }

// ---------------------------------------------------------------- audio + subtitles

@Composable
private fun AudioPane(
    tuning: PlayerTuning,
    onSelectAudio: (String) -> Unit,
    onSelectLanguage: (Int, String) -> Unit,
) {
    if (tuning.servers.isEmpty()) {
        EmptyNote(stringResource(R.string.ui_player_menu_servers_checking))
    } else {
        tuning.servers.forEach { server ->
            SectionLabel(server.label)
            if (!server.reachable) {
                EmptyNote(stringResource(R.string.ui_player_menu_server_unavailable, server.number))
            } else {
                server.languages.forEach { language ->
                    val selected = tuning.activeServer == server.number &&
                        tuning.activeLanguageId == language.id
                    SettingCard(
                        title = language.label,
                        hint = if (selected) stringResource(R.string.ui_player_menu_server_playing) else null,
                        selected = selected,
                        onClick = { onSelectLanguage(server.number, language.id) },
                    )
                }
            }
        }
    }

    SectionLabel(stringResource(R.string.ui_player_menu_section_audio_track))
    SettingCard(
        title = stringResource(R.string.ui_player_menu_audio_default),
        hint = stringResource(R.string.ui_player_menu_audio_default_hint),
        selected = tuning.selectedAudioId == TrackRow.AUTO_ID,
        glyph = Icons.Rounded.GraphicEq,
        onClick = { onSelectAudio(TrackRow.AUTO_ID) },
    )
    if (tuning.hasAudioTracks) {
        tuning.audios.forEach { track ->
            SettingCard(
                title = track.label,
                hint = null,
                selected = track.id == tuning.selectedAudioId,
                onClick = { onSelectAudio(track.id) },
            )
        }
    } else {
        EmptyNote(stringResource(R.string.ui_player_menu_audio_single))
    }

}

// ---------------------------------------------------------------- speed

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPane(tuning: PlayerTuning, onSelect: (Float) -> Unit) {
    SectionLabel(stringResource(R.string.ui_player_menu_tab_speed))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlayerSpeeds.forEach { speed ->
            Pill(
                label = formatSpeed(speed),
                selected = abs(tuning.speed - speed) < 0.001f,
                onClick = { onSelect(speed) },
            )
        }
    }
    EmptyNote(stringResource(R.string.ui_player_menu_speed_hint))
}

// ---------------------------------------------------------------- sleep

@Composable
private fun SleepPane(sleepTimer: SleepTimerState, onSelect: (Int?) -> Unit) {
    // Read here, not in the player stage: the countdown updates about once a second, and only the
    // pane that is showing those minutes should recompose for it.
    val chosen = sleepTimer.minutes
    val minutesLeft = sleepTimer.minutesLeft

    SettingCard(
        title = stringResource(R.string.ui_player_menu_sleep_off),
        hint = null,
        selected = chosen == null,
        glyph = Icons.Rounded.Bedtime,
        onClick = { onSelect(null) },
    )

    SectionLabel(stringResource(R.string.ui_player_menu_tab_sleep))
    PlayerSleepMinutes.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { minutes ->
                val selected = chosen == minutes
                val label = if (minutes == 0) {
                    stringResource(R.string.ui_player_menu_sleep_end_of_episode)
                } else {
                    stringResource(R.string.ui_player_menu_sleep_minutes, minutes)
                }
                SettingCard(
                    // The countdown belongs to the card that was actually picked, not to whichever
                    // preset happens to sit closest to the time left.
                    hint = when {
                        !selected -> null
                        minutes == 0 -> stringResource(R.string.ui_player_menu_sleep_left_end_of_episode)
                        minutesLeft > 0 ->
                            stringResource(R.string.ui_player_menu_sleep_left_minutes, minutesLeft)
                        else -> null
                    },
                    title = label,
                    selected = selected,
                    onClick = { onSelect(minutes) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- building blocks

/**
 * One selectable row/card. [highlighted] gives the "recommended" card (Auto) a slightly stronger
 * tint so it does not need a different widget to stand out.
 */
@Composable
internal fun SettingCard(
    title: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val surface by animateColorAsState(
        targetValue = when {
            selected -> colors.primary.copy(alpha = 0.14f)
            highlighted -> colors.surfaceContainerHighest.copy(alpha = 0.7f)
            else -> colors.surfaceContainer
        },
        animationSpec = tween(160),
        label = "card-bg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.75f) else colors.outlineVariant.copy(alpha = 0.55f),
        animationSpec = tween(160),
        label = "card-border",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(surface)
            .border(if (selected) SelectedBorder else IdleBorder, border, CardShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SelectionDot(selected = selected)
    }
}

@Composable
internal fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainer)
            .border(
                if (selected) SelectedBorder else IdleBorder,
                if (selected) Color.Transparent else colors.outlineVariant.copy(alpha = 0.6f),
                CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

/** A ring that fills when selected — smaller footprint than a trailing check-mark row. */
@Composable
private fun SelectionDot(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Transparent)
            .border(
                IdleBorder,
                if (selected) Color.Transparent else colors.outline.copy(alpha = 0.7f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
    )
}

@Composable
internal fun EmptyNote(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `4.2` for 4_200_000 — one decimal, never a locale-dependent comma. */
private fun formatMbps(bitrate: Int): String {
    val tenths = (bitrate / 100_000L).coerceAtLeast(0L)
    return "${tenths / 10}.${tenths % 10}"
}

// ---------------------------------------------------------------- previews

private val PreviewTuning = PlayerTuning(
    qualities = listOf(
        QualityRow("1080", "1080p", 1080, 5_000_000),
        QualityRow("720", "720p", 720, 3_000_000),
        QualityRow("480", "480p", 480, 900_000),
    ),
    activeQualityId = "720",
    audios = listOf(TrackRow("a1", "Japanese"), TrackRow("a2", "Hindi")),
    selectedAudioId = "a2",
    texts = emptyList(),
    selectedTextId = TrackRow.OFF_ID,
    speed = 1.25f,
    hasQualities = true,
    hasAudioTracks = true,
)

@Preview(name = "Player menu · quality", widthDp = 400, heightDp = 720, showBackground = true)
@Composable
private fun PlayerMenuQualityPreview() {
    AnimoTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF07090A))) {
            PlayerSettingsSheet(
                tuning = PreviewTuning,
                sleepTimer = SleepTimerState(),
                onDismiss = {},
                onSelectQuality = {},
                onSelectAudio = {},
                onSelectSpeed = {},
                onSelectSleep = {},
            )
        }
    }
}

@Preview(name = "Player menu · sleep", widthDp = 400, heightDp = 720, showBackground = true)
@Composable
private fun PlayerMenuSleepPreview() {
    AnimoTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF07090A))) {
            PlayerSettingsSheet(
                tuning = PreviewTuning,
                sleepTimer = SleepTimerState(initialMinutes = 15, initialRemainingMs = 14 * 60_000L),
                onDismiss = {},
                onSelectQuality = {},
                onSelectAudio = {},
                onSelectSpeed = {},
                onSelectSleep = {},
                initialTab = PlayerMenuTab.SLEEP,
            )
        }
    }
}
