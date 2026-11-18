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

    val schedule = TopLevelDestination(
        route = Routes.SCHEDULE,
        matchRoute = Routes.SCHEDULE,
        labelRes = R.string.ui_tab_schedule,
        icon = TabIconsOutlined.schedule,
        selectedIcon = TabIconsFilled.schedule,
    )

    val favorites = TopLevelDestination(
        route = Routes.FAVORITES,
        matchRoute = Routes.FAVORITES,
        labelRes = R.string.ui_tab_favorites,
        icon = TabIconsOutlined.favorites,
        selectedIcon = TabIconsFilled.favorites,
    )

    /** Bottom bar order: Home, Browse, Schedule, Favourites, Library. Settings lives in the drawer. */
    val tabs: List<TopLevelDestination> = listOf(home, browse, schedule, favorites, library)

    /** Everything the drawer lists at the top, and every page that keeps the shell chrome. */
    val shell: List<TopLevelDestination> = tabs + settings

    fun tabForRoute(route: String?): TopLevelDestination? =
        shell.firstOrNull { it.matchRoute == route }

    /**
     * The tab the bottom bar should highlight, or `null` when no bar belongs on this page.
     *
     * Settings keeps the shell (so the drawer stays reachable) but has no tab of its own: showing
     * the bar there left every item unselected, which reads as a bug.
     */
    fun barTabForRoute(route: String?): TopLevelDestination? =
        tabs.firstOrNull { it.matchRoute == route }
}
