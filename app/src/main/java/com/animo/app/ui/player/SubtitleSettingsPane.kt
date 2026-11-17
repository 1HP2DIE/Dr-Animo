package com.animo.app.ui.player

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.subtitle.CueAlignment
import com.animo.app.subtitle.SubtitleBackground
import com.animo.app.subtitle.SubtitlePreset
import com.animo.app.subtitle.SubtitleStyle
import com.animo.app.subtitle.SubtitleStyleLimits
import com.animo.app.subtitle.SubtitleStyles
import com.animo.app.subtitle.online.SubtitleLanguages
import com.animo.app.subtitle.online.SubtitleResult
import kotlin.math.roundToInt

/** Where the online search is in its little lifecycle. */
sealed interface OnlineSubtitlePhase {
    /** Nothing searched yet in this episode — the pane starts the search itself when it opens. */
    data object Idle : OnlineSubtitlePhase

    data object Searching : OnlineSubtitlePhase

    data class Ready(
        val results: List<SubtitleResult>,
        /** How many sources failed to answer, so the count is honest. */
        val failedSources: Int = 0,
    ) : OnlineSubtitlePhase

    data class Failed(val message: String) : OnlineSubtitlePhase
}

/** Everything the subtitle pane renders, mirrored out of the player's live state. */
@Immutable
data class SubtitlePanelState(
    val style: SubtitleStyle = SubtitleStyles.Default,
    /** Built-in presets followed by the viewer's own. */
    val presets: List<SubtitlePreset> = SubtitleStyles.builtInPresets,
    /** Name of the loaded side-loaded file, `null` when nothing is loaded. */
    val fileLabel: String? = null,
    /** Non-null while a file or link is being read, already worded for display. */
    val busyLabel: String? = null,
    val error: String? = null,
    /** Online search for the episode being played. */
    val online: OnlineSubtitlePhase = OnlineSubtitlePhase.Idle,
    /** What is being searched for, already worded: "Frieren · E5". */
    val onlineQuery: String = "",
    /** Id of the result currently downloading. */
    val downloadingId: String? = null,
    /** One-line outcome of the last download ("Loaded x.srt"), `null` when there is nothing to say. */
    val onlineNote: String? = null,
)

/** All the writes the subtitle pane can perform. One object keeps the sheet signature readable. */
class SubtitlePanelActions(
    val onSelectTrack: (String) -> Unit = {},
    val onStyleChange: (SubtitleStyle) -> Unit = {},
    val onSavePreset: (String) -> Unit = {},
    val onDeletePreset: (String) -> Unit = {},
    val onLoadFile: (Uri) -> Unit = {},
    val onLoadUrl: (String) -> Unit = {},
    val onRemoveFile: () -> Unit = {},
    val onSearchOnline: () -> Unit = {},
    val onDownloadOnline: (SubtitleResult) -> Unit = {},
)

/** Colours offered as swatches — chosen to read on video, not to fill a colour wheel. */
private val SubtitleTextColors = listOf(
    0xFFFFFFFF, 0xFFF3F7F6, 0xFFFFF3C4, 0xFF9BF2E6,
    0xFFFFD166, 0xFF9AD4FF, 0xFFFFB3C7, 0xFFFF8A80,
)

private val SubtitleOutlineColors = listOf(
    0xFF000000, 0xFF0D1113, 0xFF08303A, 0xFF2B1A05, 0xFF3A0B12, 0xFF1E1E1E,
)

/**
 * The subtitle pane of the player settings sheet.
 *
 * Three jobs in one scroll: choose a track (container, side-loaded file, or a link), pick a look
 * from presets, and tune that look with live feedback from the preview at the top. Everything is
 * written back through [actions] immediately — there is no Apply button, because the preview is the
 * confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitlePane(
    tuning: PlayerTuning,
    state: SubtitlePanelState,
    actions: SubtitlePanelActions,
) {
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showPresetDialog by rememberSaveable { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var presetName by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) actions.onLoadFile(uri)
    }

    SectionLabel(stringResource(R.string.ui_sub_preview))
    SubtitlePreviewCard(
        style = state.style,
        sample = stringResource(R.string.ui_sub_preview_line),
    )

    // ------------------------------------------------------------ tracks
    SectionLabel(stringResource(R.string.ui_sub_section_tracks))
    SettingCard(
        title = stringResource(R.string.ui_sub_track_off),
        hint = null,
        selected = tuning.selectedTextId == TrackRow.OFF_ID,
        glyph = Icons.Rounded.Subtitles,
        onClick = { actions.onSelectTrack(TrackRow.OFF_ID) },
    )

    state.fileLabel?.let { label ->
        SettingCard(
            title = stringResource(R.string.ui_sub_track_file, label),
            hint = stringResource(R.string.ui_sub_track_file_hint),
            selected = tuning.selectedTextId == TrackRow.FILE_ID,
            glyph = Icons.Rounded.UploadFile,
            onClick = { actions.onSelectTrack(TrackRow.FILE_ID) },
        )
    }

    tuning.texts.forEach { track ->
        SettingCard(
            title = track.label,
            hint = null,
            selected = track.id == tuning.selectedTextId,
            onClick = { actions.onSelectTrack(track.id) },
        )
    }

    if (tuning.texts.isEmpty() && state.fileLabel == null) {
        EmptyNote(stringResource(R.string.ui_sub_track_none))
    }

    state.busyLabel?.let { EmptyNote(it) }
    state.error?.let { ErrorNote(it) }

    ActionCard(
        glyph = Icons.Rounded.UploadFile,
        title = stringResource(R.string.ui_sub_load_file),
        hint = stringResource(R.string.ui_sub_load_file_hint),
        onClick = { picker.launch(arrayOf("*/*")) },
    )
    ActionCard(
        glyph = Icons.Rounded.Link,
        title = stringResource(R.string.ui_sub_load_url),
        hint = stringResource(R.string.ui_sub_load_url_hint),
        onClick = { showUrlDialog = true },
    )
    if (state.fileLabel != null) {
        ActionCard(
            glyph = Icons.Rounded.Delete,
            title = stringResource(R.string.ui_sub_remove_file),
            hint = null,
            tint = MaterialTheme.colorScheme.error,
            onClick = actions.onRemoveFile,
        )
    }

    // ------------------------------------------------------------ online
    SectionLabel(stringResource(R.string.ui_sub_section_online))
    LaunchedEffect(Unit) {
        // Opening the CC pane on an episode nobody has searched yet is the strongest signal that
        // the viewer wants subtitles — so search without making them ask twice.
        if (state.online is OnlineSubtitlePhase.Idle) actions.onSearchOnline()
    }
    state.onlineNote?.let { note ->
        Text(
            text = note,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    when (val online = state.online) {
        is OnlineSubtitlePhase.Idle -> ActionCard(
            glyph = Icons.Rounded.Search,
            title = stringResource(R.string.ui_sub_online_search),
            hint = stringResource(R.string.ui_sub_online_search_hint, state.onlineQuery),
            onClick = actions.onSearchOnline,
        )

        is OnlineSubtitlePhase.Searching -> EmptyNote(
            stringResource(R.string.ui_sub_online_searching, state.onlineQuery),
        )

        is OnlineSubtitlePhase.Failed -> {
            ErrorNote(online.message)
            ActionCard(
                glyph = Icons.Rounded.Refresh,
                title = stringResource(R.string.ui_sub_online_retry),
                hint = null,
                onClick = actions.onSearchOnline,
            )
        }

        is OnlineSubtitlePhase.Ready -> {
            if (online.results.isEmpty()) {
                EmptyNote(stringResource(R.string.ui_sub_online_empty))
                ActionCard(
                    glyph = Icons.Rounded.Refresh,
                    title = stringResource(R.string.ui_sub_online_retry),
                    hint = null,
                    onClick = actions.onSearchOnline,
                )
            } else {
                OnlineResults(
                    results = online.results,
                    failedSources = online.failedSources,
                    downloadingId = state.downloadingId,
                    onDownload = actions.onDownloadOnline,
                )
                ActionCard(
                    glyph = Icons.Rounded.Refresh,
                    title = stringResource(R.string.ui_sub_online_retry),
                    hint = null,
                    onClick = actions.onSearchOnline,
                )
            }
        }
    }

    // ------------------------------------------------------------ presets
    SectionLabel(stringResource(R.string.ui_sub_section_presets))
    val selectedPreset = state.presets.firstOrNull { it.style == state.style }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.presets.forEach { preset ->
            Pill(
                label = preset.name,
                selected = preset.id == selectedPreset?.id,
                onClick = { actions.onStyleChange(preset.style) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionCard(
            glyph = Icons.Rounded.BookmarkAdd,
            title = stringResource(R.string.ui_sub_preset_save),
            hint = null,
            onClick = { showPresetDialog = true },
            modifier = Modifier.weight(1f),
        )
    }
    if (selectedPreset != null && !selectedPreset.builtIn) {
        TextButton(onClick = { actions.onDeletePreset(selectedPreset.id) }) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.ui_sub_preset_delete, selectedPreset.name),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    TextButton(onClick = { actions.onStyleChange(SubtitleStyles.Default) }) {
        Text(stringResource(R.string.ui_sub_reset))
    }

    // ------------------------------------------------------------ text
    SectionLabel(stringResource(R.string.ui_sub_section_text))
    SwatchRow(
        label = stringResource(R.string.ui_sub_text_color),
        colors = SubtitleTextColors,
        selected = state.style.textColor,
        onSelect = { actions.onStyleChange(state.style.copy(textColor = it)) },
    )
    SliderSetting(
        label = stringResource(R.string.ui_sub_size),
        valueLabel = stringResource(R.string.ui_sub_value_scale, formatScale(state.style.sizeScale)),
        value = state.style.sizeScale,
        range = SubtitleStyleLimits.SIZE_MIN..SubtitleStyleLimits.SIZE_MAX,
        onChange = { actions.onStyleChange(state.style.copy(sizeScale = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            label = stringResource(R.string.ui_sub_bold),
            selected = state.style.bold,
            onClick = { actions.onStyleChange(state.style.copy(bold = !state.style.bold)) },
            modifier = Modifier.weight(1f),
        )
        Pill(
            label = stringResource(R.string.ui_sub_italic),
            selected = state.style.italic,
            onClick = { actions.onStyleChange(state.style.copy(italic = !state.style.italic)) },
            modifier = Modifier.weight(1f),
        )
        Pill(
            label = stringResource(R.string.ui_sub_uppercase),
            selected = state.style.uppercase,
            onClick = { actions.onStyleChange(state.style.copy(uppercase = !state.style.uppercase)) },
            modifier = Modifier.weight(1f),
        )
    }
    SegmentedRow(
        label = stringResource(R.string.ui_sub_alignment),
        options = listOf(
            stringResource(R.string.ui_sub_align_start) to CueAlignment.START,
            stringResource(R.string.ui_sub_align_center) to CueAlignment.CENTER,
            stringResource(R.string.ui_sub_align_end) to CueAlignment.END,
        ),
        selected = state.style.alignment,
        onSelect = { actions.onStyleChange(state.style.copy(alignment = it)) },
    )
    SliderSetting(
        label = stringResource(R.string.ui_sub_max_lines),
        valueLabel = stringResource(
            R.string.ui_sub_value_lines,
            state.style.maxLines,
        ),
        value = state.style.maxLines.toFloat(),
        range = SubtitleStyleLimits.MAX_LINES_MIN.toFloat()..SubtitleStyleLimits.MAX_LINES_MAX.toFloat(),
        steps = SubtitleStyleLimits.MAX_LINES_MAX - SubtitleStyleLimits.MAX_LINES_MIN - 1,
        onChange = {
            actions.onStyleChange(state.style.copy(maxLines = it.roundToInt()))
        },
    )
    SliderSetting(
        label = stringResource(R.string.ui_sub_letter_spacing),
        valueLabel = stringResource(R.string.ui_sub_value_em, formatScale(state.style.letterSpacing)),
        value = state.style.letterSpacing,
        range = SubtitleStyleLimits.LETTER_SPACING_MIN..SubtitleStyleLimits.LETTER_SPACING_MAX,
        onChange = { actions.onStyleChange(state.style.copy(letterSpacing = it)) },
    )

    // ------------------------------------------------------------ outline + shadow
    SectionLabel(stringResource(R.string.ui_sub_section_edge))
    SliderSetting(
        label = stringResource(R.string.ui_sub_outline_width),
        valueLabel = stringResource(R.string.ui_sub_value_dp, formatScale(state.style.outlineWidth)),
        value = state.style.outlineWidth,
        range = SubtitleStyleLimits.OUTLINE_MIN..SubtitleStyleLimits.OUTLINE_MAX,
        onChange = { actions.onStyleChange(state.style.copy(outlineWidth = it)) },
    )
    SwatchRow(
        label = stringResource(R.string.ui_sub_outline_color),
        colors = SubtitleOutlineColors,
        selected = state.style.outlineColor,
        onSelect = { actions.onStyleChange(state.style.copy(outlineColor = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            label = stringResource(R.string.ui_sub_shadow),
            selected = state.style.shadow,
            onClick = { actions.onStyleChange(state.style.copy(shadow = !state.style.shadow)) },
            modifier = Modifier.weight(1f),
        )
    }

    // ------------------------------------------------------------ background + position
    SectionLabel(stringResource(R.string.ui_sub_section_placement))
    SegmentedRow(
        label = stringResource(R.string.ui_sub_background),
        options = listOf(
            stringResource(R.string.ui_sub_bg_none) to SubtitleBackground.NONE,
            stringResource(R.string.ui_sub_bg_box) to SubtitleBackground.BOX,
            stringResource(R.string.ui_sub_bg_bar) to SubtitleBackground.BAR,
        ),
        selected = state.style.background,
        onSelect = { actions.onStyleChange(state.style.copy(background = it)) },
    )
    if (state.style.background != SubtitleBackground.NONE) {
        SliderSetting(
            label = stringResource(R.string.ui_sub_bg_opacity),
            valueLabel = stringResource(
                R.string.ui_sub_value_percent,
                formatPercent(state.style.backgroundOpacity * 100f),
            ),
            value = state.style.backgroundOpacity,
            range = SubtitleStyleLimits.OPACITY_MIN..SubtitleStyleLimits.OPACITY_MAX,
            onChange = { actions.onStyleChange(state.style.copy(backgroundOpacity = it)) },
        )
    }
    SliderSetting(
        label = stringResource(R.string.ui_sub_bottom),
        valueLabel = stringResource(
            R.string.ui_sub_value_percent,
            formatPercent(state.style.bottomPaddingPercent),
        ),
        value = state.style.bottomPaddingPercent,
        range = SubtitleStyleLimits.BOTTOM_MIN..SubtitleStyleLimits.BOTTOM_MAX,
        onChange = { actions.onStyleChange(state.style.copy(bottomPaddingPercent = it)) },
    )
    EmptyNote(stringResource(R.string.ui_sub_bottom_hint))
    SliderSetting(
        label = stringResource(R.string.ui_sub_side_margin),
        valueLabel = stringResource(
            R.string.ui_sub_value_percent,
            formatPercent(state.style.horizontalMarginPercent),
        ),
        value = state.style.horizontalMarginPercent,
        range = 0f..20f,
        onChange = { actions.onStyleChange(state.style.copy(horizontalMarginPercent = it)) },
    )

    // ------------------------------------------------------------ dialogs
    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.ui_sub_url_title)) },
            text = {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text(stringResource(R.string.ui_sub_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = urlText.isNotBlank(),
                    onClick = {
                        val url = urlText.trim()
                        showUrlDialog = false
                        urlText = ""
                        if (url.isNotBlank()) actions.onLoadUrl(url)
                    },
                ) {
                    Text(stringResource(R.string.ui_sub_url_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(stringResource(R.string.ui_sub_cancel))
                }
            },
        )
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text(stringResource(R.string.ui_sub_preset_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.ui_sub_preset_name)) },
                    placeholder = { Text(stringResource(R.string.ui_sub_preset_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        val name = presetName.trim()
                        showPresetDialog = false
                        presetName = ""
                        if (name.isNotBlank()) actions.onSavePreset(name)
                    },
                ) {
                    Text(stringResource(R.string.ui_sub_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetDialog = false }) {
                    Text(stringResource(R.string.ui_sub_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------- online results

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnlineResults(
    results: List<SubtitleResult>,
    failedSources: Int,
    downloadingId: String?,
    onDownload: (SubtitleResult) -> Unit,
) {
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val languages = remember(results) { SubtitleLanguages.presentIn(results) }
    val visible = remember(results, languageFilter) {
        results
            .filter { languageFilter == null || it.language.code == languageFilter }
            .take(MAX_ONLINE_ROWS)
    }

    val foundLabel = stringResource(R.string.ui_sub_online_found, results.size)
    val partialLabel = stringResource(R.string.ui_sub_online_partial, failedSources)
    Text(
        text = if (failedSources > 0) "$foundLabel · $partialLabel" else foundLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (languages.size > 1) {
        // One scrolling line, not ten rows of chips: a popular episode can come back in 30
        // languages, and the chips must never push the results off the pane.
        val deviceLanguage = java.util.Locale.getDefault().language
        val ordered = languages.sortedWith(
            compareBy(
                { languagePreference(it.code, deviceLanguage) },
                { it.label },
            ),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                Pill(
                    label = stringResource(R.string.ui_sub_online_all),
                    selected = languageFilter == null,
                    onClick = { languageFilter = null },
                )
            }
            items(ordered, key = { it.code }) { language ->
                Pill(
                    label = language.label,
                    selected = languageFilter == language.code,
                    onClick = { languageFilter = language.code },
                )
            }
        }
    }

    visible.forEach { result ->
        OnlineResultRow(
            result = result,
            downloading = downloadingId == result.id,
            onClick = { onDownload(result) },
        )
    }

    if (visible.size < results.count { languageFilter == null || it.language.code == languageFilter }) {
        Text(
            text = stringResource(R.string.ui_sub_online_more, MAX_ONLINE_ROWS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun OnlineResultRow(
    result: SubtitleResult,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surfaceContainer)
            .border(IdleBorder, colors.outlineVariant.copy(alpha = 0.55f), CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(result.sourceName)
                    append(" · ")
                    append(result.language.label)
                    result.format?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it.uppercase())
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** More than this in one pane is a wall of rows — the language filter is the way through it. */
private const val MAX_ONLINE_ROWS = 12

/** Device language, then English, then the two languages this app's audience asks for most. */
private fun languagePreference(code: String, deviceLanguage: String): Int = when (code) {
    deviceLanguage -> 0
    "en" -> 1
    "hi" -> 2
    "ja" -> 3
    else -> 10
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun ActionCard(
    glyph: ImageVector,
    title: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(IdleBorder, colors.outlineVariant.copy(alpha = 0.55f), CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
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
    }
}

@Composable
private fun ErrorNote(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SliderSetting(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.outlineVariant,
            ),
        )
    }
}

@Composable
private fun SwatchRow(
    label: String,
    colors: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colors.forEach { argb ->
                val isSelected = argb == selected
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { onSelect(argb) },
                        ),
                )
            }
        }
    }
}

@Composable
private fun <T> SegmentedRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (text, value) ->
                Pill(
                    label = text,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
