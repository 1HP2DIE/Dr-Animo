package com.animo.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.SyncProgress
import com.animo.app.domain.model.SyncState
import com.animo.app.ui.Routes
import com.animo.app.ui.common.BrandAvatar
import com.animo.app.ui.theme.AnimoTheme
import com.animo.app.ui.theme.ThemeMode
import com.animo.app.ui.theme.labelRes

private val DrawerWidth = 316.dp

/**
 * Modal drawer: identity header, tab mirror, secondary shortcuts, five genre jumps and the theme
 * switch. Wide enough for a genre chip row but still a sheet on phones.
 */
@Composable
fun AnimoDrawer(
    catalogueSize: Int,
    genres: List<String>,
    sync: SyncProgress,
    currentRoute: String?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit,
    onGenreSelected: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier.width(DrawerWidth)) {
        AnimoDrawerContent(
            catalogueSize = catalogueSize,
            genres = genres,
            sync = sync,
            currentRoute = currentRoute,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onNavigate = onNavigate,
            onGenreSelected = onGenreSelected,
            onSyncNow = onSyncNow,
        )
    }
}

@Composable
fun AnimoDrawerContent(
    catalogueSize: Int,
    genres: List<String>,
    sync: SyncProgress,
    currentRoute: String?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit,
    onGenreSelected: (String) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val syncing = sync.state == SyncState.RUNNING || sync.state == SyncState.THROTTLED

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
    ) {
        DrawerHeader(
            catalogueSize = catalogueSize,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 12.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Destinations.tabs.forEach { tab ->
                val selected = currentRoute == tab.matchRoute
                DrawerItem(
                    icon = if (selected) tab.selectedIcon else tab.icon,
                    label = stringResource(tab.labelRes),
                    selected = selected,
                    onClick = { onNavigate(tab.route) },
                )
            }

            DrawerDivider()

            DrawerItem(
                icon = Icons.Outlined.Language,
                label = stringResource(R.string.ui_drawer_hindi),
                selected = false,
                onClick = { onNavigate(Routes.browse(hindi = true)) },
            )
            DrawerItem(
                icon = Icons.Outlined.PlayCircle,
                label = stringResource(R.string.ui_home_continue),
                selected = false,
                onClick = { onNavigate(Routes.LIBRARY) },
            )
            DrawerItem(
                icon = Icons.Outlined.Sync,
                label = stringResource(R.string.ui_drawer_sync_now),
                selected = false,
                onClick = onSyncNow,
                trailing = { SyncProgressLabel(syncing = syncing, sync = sync) },
            )
            DrawerItem(
                icon = Icons.Outlined.Info,
                label = stringResource(R.string.ui_drawer_about),
                selected = false,
                onClick = { onNavigate(Routes.SETTINGS) },
            )

            if (genres.isNotEmpty()) {
                DrawerDivider()
                Text(
                    text = stringResource(R.string.ui_drawer_genres),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp),
                )
                GenreChips(
                    genres = genres.take(5),
                    onGenreSelected = onGenreSelected,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                )
            }
        }

        DrawerThemeFooter(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
    }
}

@Composable
private fun SyncProgressLabel(syncing: Boolean, sync: SyncProgress) {
    if (!syncing || sync.total <= 0) return
    Text(
        text = stringResource(R.string.ui_drawer_sync_progress, sync.done, sync.total),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerHeader(catalogueSize: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandAvatar(size = 64.dp)
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ui_drawer_stat, formatCount(catalogueSize)),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(
    genres: List<String>,
    onGenreSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        genres.forEach { genre ->
            GenreChip(genre = genre, onClick = { onGenreSelected(genre) })
        }
    }
}

@Composable
private fun GenreChip(genre: String, onClick: () -> Unit) {
    Text(
        text = genre,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerThemeFooter(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ui_theme_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ThemeMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == themeMode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(
                            text = stringResource(mode.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

@Preview(name = "Drawer dark", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 316, heightDp = 720)
@Composable
private fun AnimoDrawerDarkPreview() {
    AnimoTheme {
        AnimoDrawer(
            catalogueSize = 1942,
            genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy"),
            sync = SyncProgress(done = 0, total = 0, state = SyncState.IDLE),
            currentRoute = Routes.HOME,
            themeMode = ThemeMode.DARK,
            onThemeModeChange = {},
            onNavigate = {},
            onGenreSelected = {},
            onSyncNow = {},
        )
    }
}

@Preview(name = "Drawer light", showBackground = true, backgroundColor = 0xFFF4F6F5, widthDp = 316, heightDp = 720)
@Composable
private fun AnimoDrawerLightPreview() {
    AnimoTheme(darkTheme = false) {
        AnimoDrawer(
            catalogueSize = 1942,
            genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy"),
            sync = SyncProgress(done = 12, total = 95, state = SyncState.RUNNING),
            currentRoute = Routes.LIBRARY,
            themeMode = ThemeMode.SYSTEM,
            onThemeModeChange = {},
            onNavigate = {},
            onGenreSelected = {},
            onSyncNow = {},
        )
    }
}
