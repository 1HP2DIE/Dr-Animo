package com.animo.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateMapOf
import com.animo.app.domain.model.StreamServer
import com.animo.app.download.DownloadEngine
import com.animo.app.download.DownloadLanguage
import com.animo.app.download.DownloadRequest
import com.animo.app.download.LanguageSource
import com.animo.app.download.Probe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.download.DownloadEntry
import com.animo.app.download.DownloadStatus
import com.animo.app.ui.settings.DownloadQuality

/** Small download state button for an episode tile. */
@Composable
internal fun EpisodeDownloadButton(
    episodeNumber: Int,
    entry: DownloadEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val running = entry?.status == DownloadStatus.RUNNING || entry?.status == DownloadStatus.QUEUED
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (running && entry!!.progress > 0f) 6.dp else 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (running && entry!!.progress > 0f) {
            Text(
                text = "${(entry.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
            )
        }
        when (entry?.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> Box(contentAlignment = Alignment.Center) {
                if (entry.progress > 0f) {
                    CircularProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        trackColor = colors.onSurface.copy(alpha = 0.12f),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            DownloadStatus.DONE -> Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = stringResource(R.string.ui_download_downloaded),
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
            DownloadStatus.FAILED -> Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.ui_downloads_failed),
                tint = colors.error,
                modifier = Modifier.size(18.dp),
            )
            null -> Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.ui_download_episode_desc, episodeNumber),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** One row of the quality list: a height on a server, or the automatic pick. */
private data class QualityPick(val height: Int, val server: StreamServer?, val auto: Boolean = false)

/**
 * Everything a download can be: which languages (several at once: SUB and DUB together, or Hindi),
 * which quality from every server that has it, and which subtitle to keep next to the file.
 *
 * Languages come merged from all servers; picking one probes each server that offers it in
 * parallel, so qualities appear as servers answer instead of after the slowest one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DownloadOptionsSheet(
    animeId: Int,
    episode: Episode,
    preferredAudio: AudioTrack,
    defaultQuality: DownloadQuality,
    downloadedKeys: Set<String>,
    onDownload: (List<DownloadRequest>) -> Unit,
    onDismiss: () -> Unit,
) {
    val center = ServiceLocator.downloads
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var languages by remember(episode.id) { mutableStateOf<List<DownloadLanguage>?>(null) }
    var selectedKeys by remember(episode.id) { mutableStateOf(emptySet<String>()) }
    val probes = remember(episode.id) { mutableStateMapOf<String, Map<LanguageSource, Probe?>>() }
    var pick by remember(episode.id) { mutableStateOf(QualityPick(defaultQuality.targetHeight, null, auto = true)) }
    // null = automatic, "" = none, else a track label.
    var subtitle by remember(episode.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(episode.id) {
        val loaded = center.languages(animeId, episode)
        languages = loaded
        val preferredKey = if (preferredAudio == AudioTrack.DUB) "DUB" else "SUB"
        selectedKeys = setOfNotNull(
            loaded.firstOrNull { it.key == preferredKey }?.key ?: loaded.firstOrNull()?.key,
        )
    }
    // Probe every server of every selected language once.
    LaunchedEffect(selectedKeys, languages) {
        val all = languages ?: return@LaunchedEffect
        coroutineScope {
            all.filter { it.key in selectedKeys && it.key !in probes }.forEach { language ->
                probes[language.key] = language.sources.associateWith { null }
                language.sources.forEach { source ->
                    launch {
                        val probe = center.probe(animeId, episode, language, source).getOrNull()
                        probes[language.key] = probes[language.key].orEmpty() + (source to probe)
                    }
                }
            }
        }
    }

    val selected = languages.orEmpty().filter { it.key in selectedKeys }
    val answered = selected.flatMap { probes[it.key].orEmpty().values.filterNotNull() }
    val pending = selected.any { language -> probes[language.key].orEmpty().let { it.isEmpty() || it.values.any { p -> p == null } } }
    val rows = answered
        .flatMap { probe -> probe.qualities.map { QualityPick(it.height, probe.source.server) to it.label } }
        .distinctBy { it.first }
        .sortedWith(compareByDescending<Pair<QualityPick, String>> { it.first.height }.thenBy { it.first.server?.number ?: 0 })
    val subtitleLabels = answered.flatMap { it.subtitles }.distinct()
    val defaultLabel = stringResource(
        if (defaultQuality == DownloadQuality.HD) R.string.ui_download_quality_hd else R.string.ui_download_quality_highest,
    )

    fun requestsFor(): List<DownloadRequest> = selected.map { language ->
        val probed = probes[language.key].orEmpty().values.filterNotNull()
        val target = if (pick.height > 0) pick.height else Int.MAX_VALUE
        // The picked server when it has this language, else whichever server comes closest.
        val source = probed.firstOrNull { it.source.server == pick.server && !pick.auto }?.source
            ?: DownloadEngine.pickByHeight(
                probed.flatMap { probe -> probe.qualities.map { probe.source to it.height } },
                target,
            ) { it.second }?.first
            ?: language.sources.first()
        DownloadRequest(language, source, target, subtitle)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.ui_download_episode, episode.number),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SheetLabel(stringResource(R.string.ui_download_language))
            if (languages == null) {
                LoadingLine(stringResource(R.string.ui_download_loading_languages))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.orEmpty().forEach { language ->
                        val on = language.key in selectedKeys
                        val done = language.key in downloadedKeys
                        FilterChip(
                            selected = on,
                            onClick = {
                                selectedKeys = if (on) selectedKeys - language.key else selectedKeys + language.key
                            },
                            label = { Text(language.label) },
                            leadingIcon = if (done) {
                                { Icon(Icons.Rounded.DownloadDone, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (languages.orEmpty().size > 1) {
                    Text(
                        text = stringResource(R.string.ui_download_language_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SheetLabel(stringResource(R.string.ui_download_quality))
            QualityRow(
                title = stringResource(R.string.ui_download_auto, defaultLabel),
                hint = stringResource(R.string.ui_download_auto_hint),
                highlighted = pick.auto,
                onClick = { pick = QualityPick(defaultQuality.targetHeight, null, auto = true) },
            )
            rows.forEach { (row, label) ->
                QualityRow(
                    title = label,
                    hint = row.server?.label,
                    highlighted = !pick.auto && pick == row,
                    onClick = { pick = row },
                )
            }
            if (pending && selected.isNotEmpty()) LoadingLine(stringResource(R.string.ui_download_loading))
            if (!pending && selected.isNotEmpty() && answered.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_download_error_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SheetLabel(stringResource(R.string.ui_download_subtitles))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = subtitle == null,
                    onClick = { subtitle = null },
                    label = { Text(stringResource(R.string.ui_download_subtitles_auto)) },
                )
                FilterChip(
                    selected = subtitle == "",
                    onClick = { subtitle = "" },
                    label = { Text(stringResource(R.string.ui_download_subtitles_none)) },
                )
                subtitleLabels.forEach { label ->
                    FilterChip(
                        selected = subtitle == label,
                        onClick = { subtitle = label },
                        label = { Text(label) },
                    )
                }
            }

            Button(
                onClick = { onDownload(requestsFor()) },
                enabled = selected.isNotEmpty() && (answered.isNotEmpty() || !pending),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selected.size > 1) {
                        stringResource(R.string.ui_download_button_many, selected.size)
                    } else {
                        stringResource(R.string.ui_download_button)
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LoadingLine(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityRow(title: String, hint: String?, highlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) colors.primary.copy(alpha = 0.10f) else colors.surfaceContainer)
            .border(
                1.dp,
                if (highlighted) colors.primary.copy(alpha = 0.45f) else colors.outline.copy(alpha = 0.4f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.HighQuality,
            contentDescription = null,
            tint = if (highlighted) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
