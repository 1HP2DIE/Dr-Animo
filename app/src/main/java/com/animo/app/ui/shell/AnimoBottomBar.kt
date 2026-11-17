package com.animo.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.ui.Routes
import com.animo.app.ui.theme.AnimoTheme

/**
 * Four top-level tabs. Selected = accent tint + filled icon + a soft accent indicator; unselected =
 * muted outline icon. Standard 80 dp NavigationBar height, labels always visible.
 */
@Composable
fun AnimoBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer),
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = colors.outlineVariant.copy(alpha = 0.8f),
        )
        NavigationBar(
            containerColor = colors.surfaceContainer,
            tonalElevation = 0.dp,
        ) {
            Destinations.tabs.forEach { tab ->
                val selected = currentRoute == tab.matchRoute
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab.route) },
                    icon = {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(tab.labelRes),
                            maxLines = 1,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.primary,
                        indicatorColor = colors.primary.copy(alpha = 0.16f),
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Preview(name = "BottomBar dark", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 400)
@Composable
private fun AnimoBottomBarDarkPreview() {
    AnimoTheme {
        AnimoBottomBar(currentRoute = Routes.HOME, onSelect = {})
    }
}

@Preview(name = "BottomBar light", showBackground = true, backgroundColor = 0xFFF4F6F5, widthDp = 400)
@Composable
private fun AnimoBottomBarLightPreview() {
    AnimoTheme(darkTheme = false) {
        AnimoBottomBar(currentRoute = Routes.LIBRARY, onSelect = {})
    }
}
