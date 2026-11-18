package com.animo.app.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/** Outlined (unselected) counterparts of [TabIconsFilled]. */
internal object TabIconsOutlined {
    val home: ImageVector = Icons.Outlined.Home
    val browse: ImageVector = Icons.Outlined.Explore
    val library: ImageVector = Icons.Outlined.VideoLibrary
    val settings: ImageVector = Icons.Outlined.Settings
    val schedule: ImageVector = Icons.Outlined.CalendarMonth
    val favorites: ImageVector = Icons.Outlined.FavoriteBorder
}
