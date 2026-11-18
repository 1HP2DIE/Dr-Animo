package com.animo.app.ui.downloads

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animo.app.R
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.AudioTrack
import com.animo.app.download.DownloadEntry
import com.animo.app.download.DownloadStatus
import com.animo.app.download.formatSpeed
import com.animo.app.download.formatTimeLeft
import com.animo.app.ui.common.EmptyState
import kotlinx.coroutines.launch

/**
 * Every download, newest first: play it here, hand it to another player, or delete it. Running
 * downloads show live progress (the worker writes it to the same list the screen reads).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (animeId: Int, episodeId: Long, audio: AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val center = ServiceLocator.downloads
    val entries by center.entries.collectAsStateWithLifecycle()
    val sorted = remember(entries) { entries.sortedByDescending { it.createdAtMs } }
    val totalBytes = remember(entries) { entries.filter { it.status == DownloadStatus.DONE }.sumOf { it.bytesTotal } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.ui_downloads_open_with_title)
    val noPlayer = stringResource(R.string.ui_downloads_no_player)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ui_downloads_title), style = MaterialTheme.typography.titleLarge)
                        if (totalBytes > 0) {
                            Text(
                                text = stringResource(R.string.ui_downloads_storage, formatBytes(totalBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (sorted.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.ui_downloads_empty_title),
                body = stringResource(R.string.ui_downloads_empty_body),
                icon = Icons.Rounded.DownloadForOffline,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sorted, key = { it.id }) { entry ->
                DownloadRow(
                    entry = entry,
                    onPlay = {
                        center.pinForPlayback(entry)
                        val audio = AudioTrack.entries.firstOrNull { it.name == entry.audio } ?: AudioTrack.SUB
                        onPlay(entry.animeId, entry.episodeId, audio)
                    },
                    onOpenWith = {
                        val intent = center.externalPlayerIntent(entry)
                        if (intent == null) {
                            Toast.makeText(context, noPlayer, Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                context.startActivity(Intent.createChooser(intent, chooserTitle))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, noPlayer, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRetry = { scope.launch { center.retry(entry.id) } },
                    onDelete = { scope.launch { center.delete(entry.id) } },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onOpenWith: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val audioLabel = entry.languageLabel.ifBlank {
        stringResource(if (entry.audio == AudioTrack.DUB.name) R.string.label_dub else R.string.label_sub)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outline.copy(alpha = 0.4f), shape)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.animeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("E").append(entry.episodeNumber)
                        if (entry.episodeTitle.isNotBlank()) append(" · ").append(entry.episodeTitle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusLine(entry, audioLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (entry.status == DownloadStatus.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (entry.status) {
                DownloadStatus.DONE -> {
                    RowAction(Icons.Rounded.PlayArrow, stringResource(R.string.ui_downloads_play), onPlay, colors.primary)
                    RowAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.ui_downloads_open_with), onOpenWith)
                    RowAction(Icons.Rounded.Delete, stringResource(R.string.ui_downloads_delete), onDelete)
                }
                DownloadStatus.FAILED -> {
                    RowAction(Icons.Rounded.Refresh, stringResource(R.string.ui_downloads_retry), onRetry, colors.primary)
                    RowAction(Icons.Rounded.Delete, stringResource(R.string.ui_downloads_delete), onDelete)
                }
                DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
                    RowAction(Icons.Rounded.Close, stringResource(R.string.ui_downloads_cancel), onDelete)
            }
        }
        if (entry.status == DownloadStatus.RUNNING || entry.status == DownloadStatus.QUEUED) {
            if (entry.progress > 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun statusLine(entry: DownloadEntry, audioLabel: String): String {
    val quality = entry.qualityLabel.takeIf { it.isNotBlank() }
    return when (entry.status) {
        DownloadStatus.DONE -> listOfNotNull(audioLabel, quality, formatBytes(entry.bytesTotal)).joinToString(" · ")
        DownloadStatus.RUNNING -> {
            val percent = (entry.progress * 100).toInt()
            val size = if (entry.bytesTotal > 0) "${formatBytes(entry.bytesDone)} of ~${formatBytes(entry.bytesTotal)}" else formatBytes(entry.bytesDone)
            val left = entry.secondsLeft?.let { stringResource(R.string.ui_downloads_left, formatTimeLeft(it)) }
            listOfNotNull(
                audioLabel,
                "$percent%",
                size,
                entry.speedBps.takeIf { it > 0 }?.let(::formatSpeed),
                left,
            ).joinToString(" · ")
        }
        DownloadStatus.QUEUED -> "$audioLabel · ${stringResource(R.string.ui_downloads_queued)}"
        DownloadStatus.FAILED -> entry.error ?: stringResource(R.string.ui_downloads_failed)
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
