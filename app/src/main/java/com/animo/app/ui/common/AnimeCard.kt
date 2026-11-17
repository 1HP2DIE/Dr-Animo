package com.animo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.LightPosterGradientBottom
import com.animo.app.ui.theme.LightPosterGradientTop
import com.animo.app.ui.theme.AnimoTheme
import com.animo.app.ui.theme.LocalIsDarkTheme

private val CardShape = RoundedCornerShape(14.dp)

/** Paper-toned fill for a missing poster in light mode (the dark gradient read as a black hole). */
private val LightPosterPlaceholder: Brush
    get() = Brush.linearGradient(listOf(LightPosterGradientTop, LightPosterGradientBottom))

/**
 * Keeps the per-item click lambda stable across recompositions so a Room emission re-uses the
 * existing node instead of invalidating every card in the grid.
 */
@Composable
fun rememberAnimeClick(animeId: Int, onOpenAnime: (Int) -> Unit): () -> Unit =
    remember(animeId, onOpenAnime) { { onOpenAnime(animeId) } }

/**
 * Poster card: art on top, title underneath.
 *
 * The title deliberately does **not** sit on the artwork — on a 2:3 poster the scrim had to cover
 * the lower third, which both hid the art and truncated long anime titles to "Pokémon Season 9
 * Battl…". Underneath, the title is full-contrast in both themes and two lines always fit.
 *
 * The gradient placeholder + initials render underneath the image, so a missing URL, a 403 and a
 * loading frame all look identical — never a white flash.
 */
@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showScore: Boolean = true,
    showHindiBadge: Boolean = true,
    titleLines: Int = 2,
) {
    val dark = LocalIsDarkTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(CardShape)
                .background(
                    if (dark) AnimoPalette.posterPlaceholder else LightPosterPlaceholder,
                )
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CardShape),
        ) {
            Text(
                text = anime.initials(),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displaySmall,
                color = if (dark) {
                    AnimoPalette.onPosterSecondary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
            if (anime.poster.isNotBlank()) {
                AsyncImage(
                    model = anime.poster,
                    contentDescription = stringResource(R.string.ui_content_desc_poster, anime.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            // Only a hint of scrim: enough to seat the tags, not enough to grey the art.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.34f),
                            0.35f to Color.Transparent,
                        ),
                    ),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showHindiBadge && anime.isHindi) {
                    PosterTag(text = stringResource(R.string.ui_home_badge_hindi))
                }
                val score = if (showScore) formatScore(anime.score) else null
                if (score != null) {
                    PosterTag(text = "★ $score")
                }
            }
        }

        Text(
            text = anime.title,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = titleLines,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Dark glass pill for metadata that must survive on top of any artwork. */
@Composable
private fun PosterTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AnimoPalette.posterBadgeBg)
            .border(1.dp, AnimoPalette.onPoster.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = AnimoPalette.posterBadgeFg,
        maxLines = 1,
    )
}

/**
 * Wide card used by the "Continue watching" rail — same art, more room for the title, plus a
 * play affordance so the row reads as "resume", not "browse".
 */
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
            .background(if (LocalIsDarkTheme.current) AnimoPalette.posterPlaceholder else LightPosterPlaceholder)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CardShape)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = anime.initials(),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.headlineSmall,
            color = if (LocalIsDarkTheme.current) {
                AnimoPalette.onPosterSecondary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
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
        Box(Modifier.matchParentSize().background(AnimoPalette.backdropTitleScrim))

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(CircleShape)
                .background(AnimoPalette.posterBadgeBg)
                .border(1.dp, AnimoPalette.onPoster.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = AnimoPalette.onPoster,
                modifier = Modifier.size(22.dp),
            )
        }

        if (anime.isHindi) {
            PosterTag(
                text = stringResource(R.string.ui_home_badge_hindi),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }

        Text(
            text = anime.title,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 9.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = AnimoPalette.onPoster,
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

@Preview(name = "ContinueWatching", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 320)
@Composable
private fun ContinueWatchingPreview() {
    AnimoTheme {
        ContinueWatchingCard(
            anime = PreviewData.anime,
            onClick = {},
            modifier = Modifier.padding(8.dp).fillMaxWidth(0.6f),
        )
    }
}
