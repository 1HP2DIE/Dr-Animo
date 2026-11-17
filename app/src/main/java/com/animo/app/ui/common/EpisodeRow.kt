package com.animo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.Episode
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme

/**
 * Grid cell for the detail episode grid.
 *
 * The DUB badge is rendered **only** when `episode.dubUrl != null` — `is_dub` on the parent anime is
 * an episode *count* and is absent on 73.7% of rows, so it can never be used to infer this.
 */
@Composable
fun EpisodeCard(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float = 0f,
    isCurrent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val playable = episode.isPlayable()
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(shape)
            .background(if (isCurrent) colors.primary.copy(alpha = 0.10f) else AnimoPalette.surface)
            .border(
                width = 1.dp,
                color = if (isCurrent) colors.primary.copy(alpha = 0.45f) else colors.outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = episode.number.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (playable) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.45f),
            maxLines = 1,
        )
        Text(
            text = episode.displayTitle(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!episode.subUrl.isNullOrBlank()) {
                PillBadge(text = stringResource(R.string.label_sub), tone = BadgeTone.Accent)
            }
            if (episode.dubUrl != null) {
                PillBadge(text = stringResource(R.string.label_dub), tone = BadgeTone.Muted)
            }
        }
        Spacer(Modifier.weight(1f))
        EpisodeProgressTrack(fraction = watchedFraction)
    }
}

/**
 * Wide episode row: number chip, title, audio badges, optional progress. Used for the resume card
 * and for any list-shaped episode surface.
 */
@Composable
fun EpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float = 0f,
    subtitle: String? = null,
    showChevron: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AnimoPalette.surface)
            .border(1.dp, colors.outline.copy(alpha = 0.55f), shape)
            .clickable(enabled = episode.isPlayable(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = episode.number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = episode.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!episode.subUrl.isNullOrBlank()) {
                    PillBadge(text = stringResource(R.string.label_sub), tone = BadgeTone.Accent)
                }
                if (episode.dubUrl != null) {
                    PillBadge(text = stringResource(R.string.label_dub), tone = BadgeTone.Muted)
                }
                if (subtitle != null) {
                    PillBadge(text = subtitle, tone = BadgeTone.Outlined)
                }
            }
            if (watchedFraction > 0f) {
                Spacer(Modifier.height(1.dp))
                EpisodeProgressTrack(fraction = watchedFraction)
            }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Always present (even at 0) so every episode cell has identical height and the grid never jumps
 * when a progress row appears.
 */
@Composable
private fun EpisodeProgressTrack(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = 0.10f)),
    ) {
        val safe = fraction.coerceIn(0f, 1f)
        if (safe > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(safe)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
    }
}

@Preview(name = "EpisodeCard", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 130, heightDp = 120)
@Composable
private fun EpisodeCardPreview() {
    AnimoTheme {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EpisodeCard(
                episode = PreviewData.episode,
                onClick = {},
                modifier = Modifier.width(116.dp),
                watchedFraction = 0.42f,
                isCurrent = true,
            )
            EpisodeCard(
                episode = PreviewData.episode.copy(id = 1L, number = 13, dubUrl = "https://x/dub", subUrl = null),
                onClick = {},
                modifier = Modifier.width(116.dp),
            )
        }
    }
}

@Preview(name = "EpisodeRow", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 340)
@Composable
private fun EpisodeRowPreview() {
    AnimoTheme {
        EpisodeRow(
            episode = PreviewData.episode,
            onClick = {},
            watchedFraction = 0.55f,
            subtitle = "12:04 / 23:40",
            modifier = Modifier.padding(8.dp),
        )
    }
}
