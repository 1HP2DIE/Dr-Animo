package com.animo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme

private val CardShape = RoundedCornerShape(12.dp)

/**
 * Keeps the per-item click lambda stable across recompositions so a Room emission re-uses the
 * existing node instead of invalidating every card in the grid.
 */
@Composable
fun rememberAnimeClick(animeId: Int, onOpenAnime: (Int) -> Unit): () -> Unit =
    remember(animeId, onOpenAnime) { { onOpenAnime(animeId) } }

/**
 * 2:3 poster card. The gradient placeholder + initials render underneath the image, so a missing
 * URL, a 403 and a loading frame all look identical — never a white flash.
 */
@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showScore: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(CardShape)
            .background(AnimoPalette.posterPlaceholder)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CardShape)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = anime.initials(),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        if (anime.poster.isNotBlank()) {
            AsyncImage(
                model = anime.poster,
                contentDescription = stringResource(R.string.ui_content_desc_poster, anime.title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.matchParentSize().background(AnimoPalette.posterScrim))

        if (showScore) {
            val score = formatScore(anime.score)
            if (score != null) {
                PillBadge(
                    text = "★ $score",
                    tone = BadgeTone.Amber,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }

        Text(
            text = anime.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Wide card used by the "Continue watching" rail — same art, more room for the title. */
@Composable
fun ContinueWatchingCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(CardShape)
            .background(AnimoPalette.posterPlaceholder)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CardShape)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = anime.initials(),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
        val art = anime.background.ifBlank { anime.poster }
        if (art.isNotBlank()) {
            AsyncImage(
                model = art,
                contentDescription = stringResource(R.string.ui_content_desc_backdrop, anime.title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.matchParentSize().background(AnimoPalette.posterScrim))
        Text(
            text = anime.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "AnimeCard", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 132, heightDp = 232)
@Composable
private fun AnimeCardPreview() {
    AnimoTheme {
        AnimeCard(
            anime = PreviewData.anime,
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "AnimeCard no poster", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 132, heightDp = 232)
@Composable
private fun AnimeCardNoPosterPreview() {
    AnimoTheme {
        AnimeCard(
            anime = PreviewData.anime.copy(poster = "", score = null),
            onClick = {},
            modifier = Modifier.padding(8.dp),
        )
    }
}
