package com.animo.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.animo.app.ui.LocalAppContainer
import com.animo.app.ui.Routes
import com.animo.app.ui.theme.LocalThemePreference
import com.animo.app.ui.update.UpdatePrompt
import kotlinx.coroutines.launch

/**
 * App chrome: drawer + bottom bar around the nav host.
 *
 * The bottom bar only exists on the tab destinations; Settings keeps the drawer but not the bar, and
 * detail/player are immersive: no bar, no edge-swipe drawer, and the content owns the window.
 *
 * Insets: the outer Scaffold contributes only the bottom bar height, and it consumes exactly that,
 * so the per-screen Scaffolds inside the NavHost keep handling the status bar without
 * double-padding the bottom.
 */
@Composable
fun MainScaffold(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val container = LocalAppContainer.current
    val factory = remember(container) { shellViewModelFactory(container.animeRepository) }
    val shellViewModel: ShellViewModel = viewModel(factory = factory)
    val shellState by shellViewModel.state.collectAsStateWithLifecycle()
    val themePreference = LocalThemePreference.current

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = Destinations.tabForRoute(currentRoute)
    // Settings is in [Destinations.shell] but has no tab: the bar would show with nothing selected.
    val barTab = Destinations.barTabForRoute(currentRoute)

    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentTab != null,
        modifier = modifier,
        drawerContent = {
            AnimoDrawer(
                catalogueSize = shellState.catalogueSize,
                genres = shellState.genres,
                sync = shellState.sync,
                currentRoute = currentRoute,
                themeMode = themePreference.mode,
                onThemeModeChange = themePreference.onModeChange,
                onNavigate = { route ->
                    closeDrawer()
                    navController.openTopLevel(route)
                },
                onGenreSelected = { genre ->
                    closeDrawer()
                    navController.openBrowse(genre)
                },
                onSyncNow = {
                    closeDrawer()
                    shellViewModel.syncNow()
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (barTab != null) {
                    AnimoBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { route -> navController.openTopLevel(route) },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                content(openDrawer)
                // Version tracking: a newer release prompts here, once per version.
                UpdatePrompt()
            }
        }
    }
}

/** Tab navigation: one entry per tab, state preserved when coming back. */
internal fun NavHostController.openTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Browse deep link with a preset. Pops an existing Browse entry first so repeated genre jumps do
 * not stack copies of the same destination.
 */
internal fun NavHostController.openBrowse(genre: String? = null, letter: Char? = null) {
    navigate(Routes.browse(genre, letter)) {
        popUpTo(Routes.BROWSE) { inclusive = true }
        launchSingleTop = true
    }
}


/**
 * A genre tapped on a title page.
 *
 * Unlike [openBrowse] this keeps the page it was tapped on: back from the filtered list returns to
 * the title, which is where the viewer just was. Nothing is popped either, so a genre tap never
 * throws away the page underneath.
 */
internal fun NavHostController.openGenre(genre: String) {
    navigate(Routes.browse(genre = genre)) {
        launchSingleTop = true
    }
}
