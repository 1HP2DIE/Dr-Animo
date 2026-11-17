package com.animo.app.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.player.PlayerController
import com.animo.app.ui.theme.AnimoTheme
import java.util.Locale

/**
 * The sheet never grows past this share of the screen. Playback settings change what the viewer is
 * watching, so the picture has to stay visible while the menu is open — that is the whole reason the
 * pane is capped and scrolls internally instead of taking the full height.
 */
private const val SHEET_MAX_HEIGHT_FRACTION = 0.7f

/** Same scrim as the inline quality sheet in `PlayerScreen`, so both menus dim the video identically. */
private val SheetScrim = Color(0xB3000000)

/** Bitrates arrive in bits per second; every label in this sheet is Mbps with one decimal. */
private const val BITS_PER_MEGABIT = 1_000_000.0
private const val MEGABITS_FORMAT = "%.1f"

/** The four panes of the sheet. One visible at a time keeps a phone-sized sheet short. */
private enum class PlayerMenuTab(@StringRes val labelRes: Int) {
    QUALITY(R.string.ui_player_menu_tab_quality),
    AUDIO(R.string.ui_player_menu_tab_audio),
    SPEED(R.string.ui_player_menu_tab_speed),
    SLEEP(R.string.ui_player_menu_tab_sleep),
}

/**
 * Playback settings, rendered purely from [PlayerTuning].
 *
 * The pane is split into tabs rather than one long list because quality, audio, subtitles, speed and
 * the sleep timer are consulted at different moments: a viewer opening the sheet mid-episode wants
 * one answer, not five sections to scroll past. The sheet owns only its *tab* state; every selected
 * value comes from the engine snapshot and every change goes back through a callback, so a failed
 * track switch can never leave the UI claiming something the player is not doing.
 *
 * @param sleepMinutesLeft `null` = timer off, `0` = armed to stop at the end of the episode,
 * otherwise the minutes left on the countdown.
 */
@Composable
fun PlayerSettingsSheet(
    tuning: PlayerTuning,
    sleepMinutesLeft: Int?,
    onDismiss: () -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSleep: (Int?) -> Unit,
) {
    var tabIndex by rememberSaveable { mutableStateOf(PlayerMenuTab.QUALITY.ordinal) }
    val scrimInteraction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(SheetScrim)
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        PlayerSettingsPanel(
            tuning = tuning,
            sleepMinutesLeft = sleepMinutesLeft,
            tab = PlayerMenuTab.entries.getOrElse(tabIndex) { PlayerMenuTab.QUALITY },
            onSelectTab = { tabIndex = it.ordinal },
            onDismiss = onDismiss,
            onSelectQuality = onSelectQuality,
            onSelectAudio = onSelectAudio,
            onSelectText = onSelectText,
            onSelectSpeed = onSelectSpeed,
            onSelectSleep = onSelectSleep,
        )
    }
}

/**
 * The sheet surface without the scrim, split out so `@Preview` can render a specific pane — the
 * public entry point always starts on Quality.
 */
@Composable
private fun PlayerSettingsPanel(
    tuning: PlayerTuning,
    sleepMinutesLeft: Int?,
    tab: PlayerMenuTab,
    onSelectTab: (PlayerMenuTab) -> Unit,
    onDismiss: () -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectText: (String) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSleep: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetInteraction = remember { MutableInteractionSource() }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * SHEET_MAX_HEIGHT_FRACTION

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(
                interactionSource = sheetInteraction,
                indication = null,
                onClick = {},
            )
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ui_player_menu_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ui_player_menu_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        MenuTabChips(selected = tab, onSelect = onSelectTab)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            when (tab) {
                PlayerMenuTab.QUALITY -> QualityPane(
                    tuning = tuning,
                    onSelectQuality = onSelectQuality,
                )
                PlayerMenuTab.AUDIO -> AudioPane(
                    tuning = tuning,
                    onSelectAudio = onSelectAudio,
                    onSelectText = onSelectText,
                )
                PlayerMenuTab.SPEED -> SpeedPane(
                    speed = tuning.speed,
                    onSelectSpeed = onSelectSpeed,
                )
                PlayerMenuTab.SLEEP -> SleepPane(
                    sleepMinutesLeft = sleepMinutesLeft,
                    onSelectSleep = onSelectSleep,
                )
            }
        }
    }
}

@Composable
private fun MenuTabChips(
    selected: PlayerMenuTab,
    onSelect: (PlayerMenuTab) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerMenuTab.entries.forEach { tab ->
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(50),
                label = {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.onSurface.copy(alpha = 0.05f),
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = colors.primaryContainer,
                    selectedLabelColor = colors.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun QualityPane(
    tuning: PlayerTuning,
    onSelectQuality: (String) -> Unit,
) {
    if (!tuning.hasQualities) {
        BodyLine(stringResource(R.string.ui_player_menu_quality_single))
    }

    RadioRow(
        label = stringResource(R.string.ui_player_menu_auto),
        hint = stringResource(R.string.ui_player_menu_auto_hint),
        selected = tuning.activeQualityId == PlayerController.AUTO_QUALITY_ID,
        onClick = { onSelectQuality(PlayerController.AUTO_QUALITY_ID) },
    )

    // Highest rendition first: viewers open this pane to cap quality, and the cap they want is at
    // the top of the ladder they already know from every other player.
    val renditions = remember(tuning.qualities) { tuning.qualities.sortedByDescending { it.height } }
    renditions.forEach { rendition ->
        RadioRow(
            label = rendition.label,
            hint = qualityHint(rendition),
            selected = rendition.id == tuning.activeQualityId,
            onClick = { onSelectQuality(rendition.id) },
        )
    }
}

@Composable
private fun AudioPane(
    tuning: PlayerTuning,
    onSelectAudio: (String) -> Unit,
    onSelectText: (String) -> Unit,
) {
    if (!tuning.hasAudioTracks) {
        BodyLine(stringResource(R.string.ui_player_menu_audio_single))
    }

    RadioRow(
        label = stringResource(R.string.ui_player_menu_audio_default),
        hint = stringResource(R.string.ui_player_menu_audio_default_hint),
        selected = tuning.selectedAudioId == TrackRow.AUTO_ID,
        onClick = { onSelectAudio(TrackRow.AUTO_ID) },
    )
    tuning.audios.forEach { track ->
        RadioRow(
            label = track.label,
            hint = null,
            selected = track.id == tuning.selectedAudioId,
            onClick = { onSelectAudio(track.id) },
        )
    }

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
    Text(
        text = stringResource(R.string.ui_player_menu_section_subtitles),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    RadioRow(
        label = stringResource(R.string.ui_player_menu_subtitles_off),
        hint = null,
        selected = tuning.selectedTextId == TrackRow.OFF_ID,
        onClick = { onSelectText(TrackRow.OFF_ID) },
    )
    if (tuning.texts.isEmpty()) {
        BodyLine(stringResource(R.string.ui_player_menu_subtitles_empty))
    }
    tuning.texts.forEach { track ->
        RadioRow(
            label = track.label,
            hint = null,
            selected = track.id == tuning.selectedTextId,
            onClick = { onSelectText(track.id) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPane(
    speed: Float,
    onSelectSpeed: (Float) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerSpeeds.forEach { preset ->
            FilterChip(
                selected = preset == speed,
                onClick = { onSelectSpeed(preset) },
                shape = RoundedCornerShape(50),
                label = {
                    Text(
                        text = formatSpeed(preset),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SleepPane(
    sleepMinutesLeft: Int?,
    onSelectSleep: (Int?) -> Unit,
) {
    val activePreset = activeSleepPreset(sleepMinutesLeft)

    RadioRow(
        label = stringResource(R.string.ui_player_menu_sleep_off),
        hint = null,
        selected = sleepMinutesLeft == null,
        onClick = { onSelectSleep(null) },
    )
    PlayerSleepMinutes.forEach { minutes ->
        val selected = activePreset == minutes
        RadioRow(
            label = if (minutes == 0) {
                stringResource(R.string.ui_player_menu_sleep_end_of_episode)
            } else {
                stringResource(R.string.ui_player_menu_sleep_minutes, minutes)
            },
            hint = if (selected && sleepMinutesLeft != null) sleepHint(sleepMinutesLeft) else null,
            selected = selected,
            onClick = { onSelectSleep(minutes) },
        )
    }
}

/**
 * Which preset the countdown belongs to. Only the remaining time is published, not the preset that
 * started it, so the smallest preset still covering that time is the honest answer: 14 minutes left
 * reads as the 15-minute preset, 9 minutes left keeps reading 15 rather than jumping to Off.
 */
private fun activeSleepPreset(minutesLeft: Int?): Int? = when {
    minutesLeft == null -> null
    minutesLeft <= 0 -> 0
    else -> PlayerSleepMinutes.filter { it > 0 && it >= minutesLeft }.minOrNull()
        ?: PlayerSleepMinutes.maxOf { it }
}

/**
 * Quality hint under the rendition label. The engine label already carries the resolution for HLS
 * renditions, so the height is only repeated when the label lacks it — otherwise a row would read
 * "1080p" twice.
 */
@Composable
private fun qualityHint(row: QualityRow): String? {
    val labelHasHeight = row.label.contains("${row.height}p")
    val megabits = String.format(Locale.US, MEGABITS_FORMAT, row.bitrate / BITS_PER_MEGABIT)
    return when {
        row.bitrate > 0 && labelHasHeight ->
            stringResource(R.string.ui_player_menu_quality_hint_bitrate_only, megabits)
        row.bitrate > 0 ->
            stringResource(R.string.ui_player_menu_quality_hint, row.height, megabits)
        labelHasHeight -> null
        else -> stringResource(R.string.ui_player_menu_quality_hint_plain, row.height)
    }
}

@Composable
private fun sleepHint(minutesLeft: Int): String = if (minutesLeft <= 0) {
    stringResource(R.string.ui_player_menu_sleep_left_end_of_episode)
} else {
    stringResource(R.string.ui_player_menu_sleep_left_minutes, minutesLeft)
}

/**
 * One option in a single-choice list. Uses `selectable` with [Role.RadioButton] so TalkBack
 * announces "selected" and "radio button" instead of reading a bare row of text.
 */
@Composable
private fun RadioRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Explanation that replaces an option list the stream cannot offer — never an empty section. */
@Composable
private fun BodyLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(
    name = "Player menus · quality",
    showBackground = true,
    backgroundColor = 0xFF0D1113,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun PlayerSettingsQualityPreview() {
    AnimoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(SheetScrim),
            contentAlignment = Alignment.BottomCenter,
        ) {
            PlayerSettingsPanel(
                tuning = PlayerTuning(
                    qualities = listOf(
                        QualityRow(id = "1920x1080@4200000", label = "1080p", height = 1080, bitrate = 4_200_000),
                        QualityRow(id = "1280x720@2500000", label = "720p", height = 720, bitrate = 2_500_000),
                        QualityRow(id = "854x480@800000", label = "480p", height = 480, bitrate = 800_000),
                    ),
                    activeQualityId = "1280x720@2500000",
                    hasQualities = true,
                ),
                sleepMinutesLeft = null,
                tab = PlayerMenuTab.QUALITY,
                onSelectTab = {},
                onDismiss = {},
                onSelectQuality = {},
                onSelectAudio = {},
                onSelectText = {},
                onSelectSpeed = {},
                onSelectSleep = {},
            )
        }
    }
}

@Preview(
    name = "Player menus · sleep armed",
    showBackground = true,
    backgroundColor = 0xFF0D1113,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun PlayerSettingsSleepPreview() {
    AnimoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(SheetScrim),
            contentAlignment = Alignment.BottomCenter,
        ) {
            PlayerSettingsPanel(
                tuning = PlayerTuning(),
                sleepMinutesLeft = 15,
                tab = PlayerMenuTab.SLEEP,
                onSelectTab = {},
                onDismiss = {},
                onSelectQuality = {},
                onSelectAudio = {},
                onSelectText = {},
                onSelectSpeed = {},
                onSelectSleep = {},
            )
        }
    }
}
