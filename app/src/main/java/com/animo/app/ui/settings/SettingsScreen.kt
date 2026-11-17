package com.animo.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.BuildConfig
import com.animo.app.R
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.SyncState
import com.animo.app.ui.changelog.Changelog
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.common.BrandMark
import com.animo.app.ui.shell.AnimoTopBar
import com.animo.app.ui.shell.formatCount
import com.animo.app.ui.theme.LocalThemePreference
import com.animo.app.ui.theme.ThemeMode
import com.animo.app.ui.theme.labelRes
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    onOpenChangelog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current.applicationContext
    val factory = remember(container, context) {
        settingsViewModelFactory(container.animeRepository, context)
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val themePreference = LocalThemePreference.current

    SettingsScreenContent(
        state = state,
        themeMode = themePreference.mode,
        onThemeModeChange = themePreference.onModeChange,
        onAutoplayChange = viewModel::setAutoplayNext,
        onDefaultAudioChange = viewModel::setDefaultAudio,
        onSyncNow = viewModel::syncNow,
        onOpenDrawer = onOpenDrawer,
        onOpenChangelog = {
            viewModel.markChangelogSeen()
            onOpenChangelog()
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    state: SettingsUiState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAutoplayChange: (Boolean) -> Unit,
    onDefaultAudioChange: (AudioTrack) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenChangelog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AnimoTopBar(
                title = stringResource(R.string.ui_settings_title),
                onOpenDrawer = onOpenDrawer,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "appearance") {
                AppearanceSection(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
            }
            item(key = "catalogue") {
                CatalogueSection(state = state, onSyncNow = onSyncNow)
            }
            item(key = "playback") {
                PlaybackSection(
                    autoplayNext = state.autoplayNext,
                    defaultAudio = state.defaultAudio,
                    onAutoplayChange = onAutoplayChange,
                    onDefaultAudioChange = onDefaultAudioChange,
                )
            }
            item(key = "about") {
                AboutSection(
                    changelogUnseen = Changelog.isUnseen(state.changelogSeenVersion),
                    onOpenChangelog = onOpenChangelog,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.ui_settings_appearance)) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onThemeModeChange(mode) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(mode.labelRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CatalogueSection(
    state: SettingsUiState,
    onSyncNow: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.ui_settings_catalogue)) {
        SettingsValueRow(
            label = stringResource(R.string.ui_settings_total_titles),
            value = formatCount(state.catalogueSize),
        )
        SettingsValueRow(
            label = stringResource(R.string.ui_settings_storage),
            value = formatBytes(state.catalogueBytes),
        )
        SettingsValueRow(
            label = stringResource(R.string.ui_settings_last_sync),
            value = formatSyncTime(state.lastSyncEpoch)
                ?: stringResource(R.string.ui_settings_never_synced),
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSyncNow,
            enabled = !state.isSyncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    if (state.isSyncing) R.string.ui_settings_syncing else R.string.ui_settings_sync_now,
                ),
            )
        }

        if (state.isSyncing && state.sync.total > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (state.sync.state == SyncState.THROTTLED) {
                        R.string.ui_sync_throttled_progress
                    } else {
                        R.string.ui_sync_progress
                    },
                    state.sync.done,
                    state.sync.total,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.syncMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.ui_settings_sync_failed, state.syncMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSection(
    autoplayNext: Boolean,
    defaultAudio: AudioTrack,
    onAutoplayChange: (Boolean) -> Unit,
    onDefaultAudioChange: (AudioTrack) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.ui_settings_playback)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui_settings_autoplay),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ui_settings_autoplay_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoplayNext, onCheckedChange = onAutoplayChange)
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )

        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.ui_settings_default_audio),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val tracks = AudioTrack.entries
                tracks.forEachIndexed { index, audio ->
                    SegmentedButton(
                        selected = defaultAudio == audio,
                        onClick = { onDefaultAudioChange(audio) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tracks.size),
                        label = {
                            Text(
                                text = stringResource(
                                    if (audio == AudioTrack.SUB) R.string.label_sub else R.string.label_dub,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AboutSection(
    changelogUnseen: Boolean,
    onOpenChangelog: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.ui_settings_about)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(size = 40.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ui_settings_version_value, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SettingsActionRow(
            label = stringResource(R.string.ui_settings_whats_new),
            value = stringResource(R.string.ui_settings_version_value, BuildConfig.VERSION_NAME),
            badge = if (changelogUnseen) stringResource(R.string.ui_settings_badge_new) else null,
            onClick = onOpenChangelog,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsValueRow(
            label = stringResource(R.string.ui_settings_data_source),
            value = stringResource(R.string.ui_settings_data_source_value),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_settings_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A tappable settings row. [badge] renders a small accent pill before the chevron — used for "New"
 * on the release-notes row, so the changelog shows up on its own instead of needing a launch dialog.
 */
@Composable
private fun SettingsActionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSyncTime(epochMs: Long?): String? = epochMs?.let {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
