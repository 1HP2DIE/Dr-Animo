package com.animo.app.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animo.app.AppLog
import com.animo.app.R
import com.animo.app.data.update.UpdateCenter
import com.animo.app.ui.changelog.ChangeKind
import com.animo.app.ui.changelog.Release
import java.util.Locale

/**
 * The in-app update notice.
 *
 * Placed once, at the app shell, so any screen can trigger it. It never blocks start-up: the feed
 * fetch is asynchronous and a failure simply leaves [UpdateUiState.visible] false.
 *
 * "Download APK" opens the release asset in the browser rather than installing silently — Android
 * only allows a package installer to run from a user-initiated intent, and sideloading stays the
 * user's decision.
 */
@Composable
fun UpdatePrompt(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val center = remember(context) { UpdateCenter.get(context) }
    val viewModel: UpdateViewModel = viewModel(factory = updateViewModelFactory(center))
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.visible) return
    val latest = state.latest ?: return

    AlertDialog(
        onDismissRequest = { viewModel.dismiss() },
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.ui_update_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(context.getString(R.string.ui_update_version, latest.version))
                        if (latest.date.isNotBlank()) append(" · ").append(latest.date)
                        if (latest.sizeBytes > 0) append(" · ").append(formatSize(latest.sizeBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.releases.isEmpty()) {
                    if (latest.headline.isNotBlank()) {
                        Text(
                            text = latest.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    state.releases.forEach { release ->
                        ReleaseBlock(release = release)
                    }
                }
                Text(
                    text = stringResource(
                        R.string.ui_update_current,
                        state.currentVersion,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { openApk(context, latest.apkUrl) }) {
                Text(stringResource(R.string.ui_update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismiss() }) {
                Text(stringResource(R.string.ui_update_later))
            }
        },
    )
}

@Composable
private fun ReleaseBlock(release: Release) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = release.version,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = release.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (release.headline.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = release.headline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        release.changes.forEach { change ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(changeKindColor(change.kind)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = change.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun changeKindColor(kind: ChangeKind) = when (kind) {
    ChangeKind.NEW -> MaterialTheme.colorScheme.primary
    ChangeKind.IMPROVED -> MaterialTheme.colorScheme.secondary
    ChangeKind.FIXED -> MaterialTheme.colorScheme.tertiary
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

/** Hands the APK URL to the browser; the system installer takes over from there. */
private fun openApk(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AppLog.w("no browser for ${url.take(48)}…: ${e.message}")
    }
}
