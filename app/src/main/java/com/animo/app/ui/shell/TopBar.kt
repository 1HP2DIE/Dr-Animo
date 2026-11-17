package com.animo.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.animo.app.R
import com.animo.app.ui.theme.AnimoTheme

/**
 * Shared compact top bar for shell pages (Settings today). Transparent container so it melts into
 * the background, with the drawer entry on the left when the host page is top-level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            if (onOpenDrawer != null) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = stringResource(R.string.ui_drawer_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Preview(name = "TopBar", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 400)
@Composable
private fun AnimoTopBarPreview() {
    AnimoTheme {
        AnimoTopBar(title = "Settings", subtitle = "Offline-first anime", onOpenDrawer = {})
    }
}
