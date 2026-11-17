package com.animo.app.ui.shell

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.animo.app.R
import com.animo.app.ui.Routes

/**
 * Bottom-bar / drawer entry.
 *
 * [route] is what we navigate to, [matchRoute] is what `NavDestination.route` reports for it — they
 * differ for Browse, whose destination is a pattern (`browse?genre=…&letter=…`).
 */
data class TopLevelDestination(
    val route: String,
    val matchRoute: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

object Destinations {

    val home = TopLevelDestination(
        route = Routes.HOME,
        matchRoute = Routes.HOME,
        labelRes = R.string.ui_tab_home,
        icon = TabIconsOutlined.home,
        selectedIcon = TabIconsFilled.home,
    )

    val browse = TopLevelDestination(
        route = Routes.BROWSE_BASE,
        matchRoute = Routes.BROWSE,
        labelRes = R.string.ui_tab_browse,
        icon = TabIconsOutlined.browse,
        selectedIcon = TabIconsFilled.browse,
    )

    val library = TopLevelDestination(
        route = Routes.LIBRARY,
        matchRoute = Routes.LIBRARY,
        labelRes = R.string.ui_tab_library,
        icon = TabIconsOutlined.library,
        selectedIcon = TabIconsFilled.library,
    )

    val settings = TopLevelDestination(
        route = Routes.SETTINGS,
        matchRoute = Routes.SETTINGS,
        labelRes = R.string.ui_tab_settings,
        icon = TabIconsOutlined.settings,
        selectedIcon = TabIconsFilled.settings,
    )

    /** Bottom bar order: Home, Browse, Library, Settings. */
    val tabs: List<TopLevelDestination> = listOf(home, browse, library, settings)

    fun tabForRoute(route: String?): TopLevelDestination? =
        tabs.firstOrNull { it.matchRoute == route }
}
