package com.animo.app.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Filled tab icons. Kept in their own file because Compose declares `filled.Home` and
 * `outlined.Home` as same-named extension properties — two files is the only way to import both
 * without aliasing.
 */
internal object TabIconsFilled {
    val home: ImageVector = Icons.Filled.Home
    val browse: ImageVector = Icons.Filled.Explore
    val library: ImageVector = Icons.Filled.VideoLibrary
    val settings: ImageVector = Icons.Filled.Settings
}
