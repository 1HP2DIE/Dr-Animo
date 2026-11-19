package com.animo.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.animo.app.domain.model.AudioTrack
import com.animo.app.ui.browse.BrowseScreen
import com.animo.app.ui.changelog.ChangelogScreen
import com.animo.app.ui.detail.DetailScreen
import com.animo.app.ui.downloads.DownloadsScreen
import com.animo.app.ui.home.HomeScreen
import com.animo.app.ui.library.LibraryScreen
import com.animo.app.ui.player.PlayerScreen
import com.animo.app.ui.settings.SettingsScreen
import com.animo.app.ui.shell.MainScaffold
import com.animo.app.ui.shell.openGenre
import com.animo.app.ui.shell.openTopLevel
import com.animo.app.ui.theme.AnimoAppTheme
import com.animo.app.ui.theme.LocalThemePreference
import com.animo.app.ui.theme.rememberThemePreference

const val ARG_ANIME_ID = "animeId"
const val ARG_EPISODE_ID = "episodeId"
const val ARG_AUDIO = "audio"
const val ARG_GENRE = "genre"
const val ARG_LETTER = "letter"
const val ARG_HINDI = "hindi"

/**
 * String routes (navigation-compose 2.8.5). Kept as a single object so call sites never hand-build
 * a path and the argument names live next to the route templates.
 */
object Routes {
    const val HOME = "home"

    /** Navigable base for Browse; the destination pattern adds the optional filters. */
    const val BROWSE_BASE = "browse"
    const val BROWSE =
        "$BROWSE_BASE?$ARG_GENRE={$ARG_GENRE}&$ARG_LETTER={$ARG_LETTER}&$ARG_HINDI={$ARG_HINDI}"

    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val CHANGELOG = "changelog"
    const val DOWNLOADS = "downloads"
    const val FAVORITES = "favorites"
    const val SCHEDULE = "schedule"
    const val SUPPORT = "support"
    const val DETAIL = "detail/{$ARG_ANIME_ID}?$ARG_EPISODE_ID={$ARG_EPISODE_ID}&$ARG_AUDIO={$ARG_AUDIO}"
    const val PLAYER = "player/{$ARG_ANIME_ID}/{$ARG_EPISODE_ID}/{$ARG_AUDIO}"

    /**
     * Browse with an optional preset. Empty args collapse to the plain `browse` route.
     *
     * [hindi] is written as the literal `hindi=true` (never `false`), so the plain Browse route stays
     * untouched for every existing caller and the flag survives process death as a route argument.
     * Tapping the drawer's Hindi row therefore lands on `browse?hindi=true`.
     */
    fun browse(genre: String? = null, letter: Char? = null, hindi: Boolean = false): String {
        val query = buildList {
            if (!genre.isNullOrBlank()) add("$ARG_GENRE=${Uri.encode(genre)}")
            if (letter != null) add("$ARG_LETTER=${Uri.encode(letter.toString())}")
            if (hindi) add("$ARG_HINDI=true")
        }
        return if (query.isEmpty()) BROWSE_BASE else "$BROWSE_BASE?${query.joinToString("&")}"
    }

    fun detail(animeId: Int): String = "detail/$animeId"

    /** The title page with [episodeId] already playing at its top (or full screen, per settings). */
    fun watch(animeId: Int, episodeId: Long, audio: AudioTrack): String =
        "detail/$animeId?$ARG_EPISODE_ID=$episodeId&$ARG_AUDIO=${audio.name}"

    fun player(animeId: Int, episodeId: Long, audio: AudioTrack): String =
        "player/$animeId/$episodeId/${audio.name}"
}

/**
 * App entry inside `MainActivity`'s `AnimoTheme { CompositionLocalProvider(LocalAppContainer …) }`.
 *
 * Layers, outside in: persisted theme -> drawer/bottom bar chrome -> nav graph. Detail and the
 * player are registered here too; they simply get no bottom bar because the shell hides it for
 * non-top-level destinations.
 */
@Composable
fun AnimoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
) {
    val themePreference = rememberThemePreference()

    AnimoAppTheme(themePreference) {
        CompositionLocalProvider(LocalThemePreference provides themePreference) {
            MainScaffold(navController = navController, modifier = modifier) { openDrawer ->
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { NavMotion.enter(initialState.destination.route, targetState.destination.route) },
                    exitTransition = { NavMotion.exit(initialState.destination.route, targetState.destination.route) },
                    popEnterTransition = { NavMotion.popEnter(initialState.destination.route, targetState.destination.route) },
                    popExitTransition = { NavMotion.popExit(initialState.destination.route, targetState.destination.route) },
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                            onOpenSearch = { navController.openTopLevel(Routes.BROWSE_BASE) },
                            onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                            onOpenDrawer = openDrawer,
                            onOpenLibrary = { navController.openTopLevel(Routes.LIBRARY) },
                            onOpenBrowse = { navController.openTopLevel(Routes.BROWSE_BASE) },
                            onOpenHindi = { navController.openTopLevel(Routes.browse(hindi = true)) },
                            onOpenSchedule = { navController.openTopLevel(Routes.SCHEDULE) },
                            onOpenFavorites = { navController.openTopLevel(Routes.FAVORITES) },
                        )
                    }

                    composable(
                        route = Routes.BROWSE,
                        arguments = listOf(
                            navArgument(ARG_GENRE) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument(ARG_LETTER) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument(ARG_HINDI) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        BrowseScreen(
                            initialGenre = entry.arguments?.getString(ARG_GENRE),
                            initialLetter = entry.arguments?.getString(ARG_LETTER),
                            initialHindi = entry.arguments?.getString(ARG_HINDI) == "true",
                            modifier = Modifier.fillMaxSize(),
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                        )
                    }

                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            modifier = Modifier.fillMaxSize(),
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                            onResume = { animeId, episodeId, audio ->
                                navController.navigate(Routes.watch(animeId, episodeId, audio))
                            },
                            onBrowse = { navController.openTopLevel(Routes.BROWSE_BASE) },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onOpenDrawer = openDrawer,
                            onOpenChangelog = { navController.navigate(Routes.CHANGELOG) },
                            onOpenSupport = { navController.navigate(Routes.SUPPORT) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(Routes.FAVORITES) {
                        com.animo.app.ui.favorites.FavoritesScreen(
                            onOpenDrawer = openDrawer,
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                            onBrowse = { navController.openTopLevel(Routes.BROWSE_BASE) },
                        )
                    }

                    composable(Routes.SCHEDULE) {
                        com.animo.app.ui.schedule.ScheduleScreen(
                            onOpenDrawer = openDrawer,
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                        )
                    }

                    composable(Routes.DOWNLOADS) {
                        DownloadsScreen(
                            onBack = { navController.backIfResumed() },
                            onPlay = { animeId, episodeId, audio ->
                                navController.navigate(Routes.watch(animeId, episodeId, audio))
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(Routes.SUPPORT) {
                        com.animo.app.ui.support.SupportScreen(
                            onBack = { navController.backIfResumed() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(Routes.CHANGELOG) {
                        ChangelogScreen(
                            onBack = { navController.backIfResumed() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(
                            navArgument(ARG_ANIME_ID) { type = NavType.IntType },
                            navArgument(ARG_EPISODE_ID) {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                            navArgument(ARG_AUDIO) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                    ) { entry ->
                        val arguments = entry.arguments ?: return@composable
                        val animeId = arguments.getInt(ARG_ANIME_ID)
                        DetailScreen(
                            animeId = animeId,
                            onBack = { navController.backIfResumed() },
                            onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                            onOpenGenre = { genre -> navController.openGenre(genre) },
                            autoplayEpisodeId = arguments.getLong(ARG_EPISODE_ID).takeIf { it > 0L },
                            autoplayAudio = AudioTrack.entries.firstOrNull { it.name == arguments.getString(ARG_AUDIO) },
                        )
                    }

                    composable(
                        route = Routes.PLAYER,
                        arguments = listOf(
                            navArgument(ARG_ANIME_ID) { type = NavType.IntType },
                            navArgument(ARG_EPISODE_ID) { type = NavType.LongType },
                            navArgument(ARG_AUDIO) { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val arguments = entry.arguments ?: return@composable
                        val animeId = arguments.getInt(ARG_ANIME_ID)
                        val episodeId = arguments.getLong(ARG_EPISODE_ID)
                        val audio = AudioTrack.entries
                            .firstOrNull { it.name == arguments.getString(ARG_AUDIO) }
                            ?: AudioTrack.SUB
                        PlayerScreen(
                            animeId = animeId,
                            episodeId = episodeId,
                            initialAudio = audio,
                            onBack = { navController.backIfResumed() },
                            // The timer stopped playback and let the screen go; leaving the player
                            // is what stops the app holding the display awake.
                            onSleepTimerEnded = { navController.backIfResumed() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Back, ignoring a second tap that lands before the first one has been handled.
 *
 * `popBackStack()` on a destination that is already leaving pops the one underneath it — tap twice
 * quickly enough and the start destination goes too, leaving an empty host.
 */
private fun NavHostController.backIfResumed() {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) popBackStack()
}
