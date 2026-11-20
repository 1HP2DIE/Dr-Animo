package com.animo.app.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.domain.model.ContentKind
import com.animo.app.domain.model.ProviderServer
import com.animo.app.domain.model.SyncMode
import com.animo.app.ui.common.BadgeTone
import com.animo.app.ui.common.PillBadge
import com.animo.app.ui.shell.formatCount
import com.animo.app.ui.theme.AnimoTheme
import java.util.Locale

/**
 * Provider page: one card per source, read as `provider -> [kinds] -> [servers]`.
 *
 * Pushed from Settings rather than living in the drawer, so it carries its own back arrow. The
 * segmented control and the sync button are per card: a sync running on one source leaves the
 * others tappable.
 */
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember { ProvidersViewModel.factory() }
    val viewModel: ProvidersViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    ProvidersScreenContent(
        state = state,
        onBack = onBack,
        onSyncModeChange = viewModel::onSyncModeChange,
        onSync = viewModel::onSync,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersScreenContent(
    state: ProvidersUiState,
    onBack: () -> Unit,
    onSyncModeChange: (String, SyncMode) -> Unit,
    onSync: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Providers",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.unavailable) {
                item(key = "unavailable") { UnavailableNote() }
            }
            items(items = state.rows, key = { it.id }) { row ->
                ProviderCard(
                    row = row,
                    onSyncModeChange = { mode -> onSyncModeChange(row.id, mode) },
                    onSync = { onSync(row.id) },
                )
            }
        }
    }
}

/** Said once, at the top: one missing wire is a fact about the build, not about any one provider. */
@Composable
private fun UnavailableNote() {
    Text(
        text = "Sync settings are unavailable in this build. Counts are not shown.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    row: ProviderRow,
    onSyncModeChange: (SyncMode) -> Unit,
    onSync: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(tint.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint.onSurface,
                )
                if (row.summary.isNotBlank()) {
                    Text(
                        text = row.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = tint.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = titlesLabel(row.count),
                style = MaterialTheme.typography.labelLarge,
                color = tint.onSurfaceVariant,
            )
        }

        if (row.languages.isNotEmpty() || row.kinds.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.languages.forEach { tag ->
                    PillBadge(text = languageLabel(tag), tone = BadgeTone.Muted)
                }
                row.kinds.forEach { kind ->
                    PillBadge(text = kind.label, tone = BadgeTone.Outlined)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = SyncMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = row.syncMode == mode,
                    onClick = { onSyncModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(
                            text = modeLabel(mode),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSync,
            enabled = !row.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (row.syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = tint.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text = if (row.syncing) "Syncing…" else "Sync now")
        }

        // Shown for the whole run, not only once a page lands: a sync still waiting for its first
        // answer is exactly when the viewer needs to see that something is happening. With no total
        // yet the bar is indeterminate and the counter is omitted.
        if (row.syncing) {
            Spacer(Modifier.height(10.dp))
            if (row.total > 0) {
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = tint.primary,
                    trackColor = tint.onSurface.copy(alpha = 0.08f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${row.done} / ${row.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = tint.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = tint.primary,
                    trackColor = tint.onSurface.copy(alpha = 0.08f),
                )
            }
        }

        row.message?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = tint.error,
            )
        }

        // The shape the page exists for: each kind the provider carries, with its own servers.
        row.visibleKinds.forEach { kind ->
            HorizontalDivider(
                thickness = 1.dp,
                color = tint.outlineVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text(
                text = kind.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint.primary,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.serversByKind[kind].orEmpty().forEach { server ->
                    PillBadge(text = server.label, tone = BadgeTone.Accent)
                }
            }
        }
    }
}

/** `null` until the first read lands; 1 is singular because "1 titles" reads as a bug. */
private fun titlesLabel(count: Int?): String = when (count) {
    null -> "—"
    1 -> "1 title"
    else -> "${formatCount(count)} titles"
}

private fun modeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.AUTO -> "Auto"
    SyncMode.MANUAL -> "Manual"
    SyncMode.OFF -> "Off"
}

/** ISO-639-1 in, a localised language name out; a tag the platform cannot name stays as written. */
private fun languageLabel(tag: String): String {
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
    return name.ifBlank { tag.uppercase(Locale.US) }
}

// ---------------------------------------------------------------------------- preview

@Preview(
    name = "Providers",
    showBackground = true,
    backgroundColor = 0xFF0D1113,
    widthDp = 390,
    heightDp = 900,
)
@Composable
private fun ProvidersPreview() {
    AnimoTheme {
        ProvidersScreenContent(
            state = ProvidersUiState(
                rows = listOf(
                    ProviderRow(
                        id = "anikoto",
                        displayName = "Anime",
                        summary = "The main catalogue: airing and finished anime.",
                        languages = listOf("ja"),
                        kinds = listOf(ContentKind.ANIME),
                        count = 9_404,
                        syncMode = SyncMode.AUTO,
                        serversByKind = mapOf(
                            ContentKind.ANIME to listOf(
                                ProviderServer("megaplay", "Primary"),
                                ProviderServer("mirror", "Mirror"),
                                ProviderServer("archive", "Archive"),
                            ),
                        ),
                    ),
                    ProviderRow(
                        id = "hindi",
                        displayName = "Hindi",
                        summary = "Hindi-dubbed anime and films.",
                        languages = listOf("hi"),
                        kinds = listOf(ContentKind.ANIME, ContentKind.MOVIE),
                        count = 412,
                        syncMode = SyncMode.MANUAL,
                        syncing = true,
                        done = 3,
                        total = 12,
                        serversByKind = mapOf(
                            ContentKind.ANIME to listOf(
                                ProviderServer("hindi_1", "Server 1"),
                                ProviderServer("hindi_2", "Server 2"),
                            ),
                            ContentKind.MOVIE to listOf(
                                ProviderServer("hindi_1", "Server 1"),
                            ),
                        ),
                    ),
                ),
            ),
            onBack = {},
            onSyncModeChange = { _, _ -> },
            onSync = {},
        )
    }
}
