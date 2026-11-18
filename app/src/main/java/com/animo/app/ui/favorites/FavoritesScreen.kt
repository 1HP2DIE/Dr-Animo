package com.animo.app.ui.favorites

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.Anime
import com.animo.app.ui.common.AnimeCard
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.common.rememberAnimeClick
import com.animo.app.ui.shell.AnimoTopBar

/** Every hearted title, newest first. */
@Composable
fun FavoritesScreen(
    onOpenDrawer: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember { ServiceLocator.favorites.observeFavorites() }
    val favorites: List<Anime>? by flow.collectAsState(initial = null)

    Column(modifier.fillMaxSize()) {
        AnimoTopBar(
            title = stringResource(R.string.ui_favorites_title),
            subtitle = favorites?.takeIf { it.isNotEmpty() }?.let {
                pluralStringResource(R.plurals.ui_favorites_count, it.size, it.size)
            },
            onOpenDrawer = onOpenDrawer,
        )
        val list = favorites ?: return@Column
        if (list.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.ui_favorites_empty_title),
                body = stringResource(R.string.ui_favorites_empty_body),
                icon = Icons.Rounded.FavoriteBorder,
                actionLabel = stringResource(R.string.browse_library_empty_action),
                onAction = onBrowse,
            )
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(list, key = { it.id }) { anime ->
                AnimeCard(
                    anime = anime,
                    onClick = rememberAnimeClick(anime.id, onOpenAnime),
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(220),
                        fadeOutSpec = tween(160),
                    ),
                    titleLines = 2,
                )
            }
        }
    }
}
