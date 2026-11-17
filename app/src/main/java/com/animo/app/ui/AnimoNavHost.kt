package com.animo.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.animo.app.ui.home.HomeScreen
import com.animo.app.ui.library.LibraryScreen
import com.animo.app.ui.player.PlayerScreen
import com.animo.app.ui.settings.SettingsScreen
import com.animo.app.ui.shell.MainScaffold
import com.animo.app.ui.shell.openTopLevel
import com.animo.app.ui.theme.AnimoAppTheme
import com.animo.app.ui.theme.LocalThemePreference
import com.animo.app.ui.theme.rememberThemePreference

const val ARG_ANIME_ID = "animeId"
const val ARG_EPISODE_ID = "episodeId"
const val ARG_AUDIO = "audio"
const val ARG_GENRE = "genre"
const val ARG_LETTER = "letter"

/**
 * String routes (navigation-compose 2.8.5). Kept as a single object so call sites never hand-build
 * a path and the argument names live next to the route templates.
 */
object Routes {
    const val HOME = "home"

    /** Navigable base for Browse; the destination pattern adds the optional filters. */
    const val BROWSE_BASE = "browse"
    const val BROWSE = "$BROWSE_BASE?$ARG_GENRE={$ARG_GENRE}&$ARG_LETTER={$ARG_LETTER}"

    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val CHANGELOG = "changelog"
    const val DETAIL = "detail/{$ARG_ANIME_ID}"
    const val PLAYER = "player/{$ARG_ANIME_ID}/{$ARG_EPISODE_ID}/{$ARG_AUDIO}"

    /** Browse with an optional preset. Empty args collapse to the plain `browse` route. */
    fun browse(genre: String? = null, letter: Char? = null): String {
        val query = buildList {
            if (!genre.isNullOrBlank()) add("$ARG_GENRE=${Uri.encode(genre)}")
            if (letter != null) add("$ARG_LETTER=${Uri.encode(letter.toString())}")
        }
        return if (query.isEmpty()) BROWSE_BASE else "$BROWSE_BASE?${query.joinToString("&")}"
    }

    fun detail(animeId: Int): String = "detail/$animeId"

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
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                            onOpenSearch = { navController.openTopLevel(Routes.BROWSE_BASE) },
                            onOpenDrawer = openDrawer,
                            onOpenLibrary = { navController.openTopLevel(Routes.LIBRARY) },
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
                        ),
                    ) { entry ->
                        BrowseScreen(
                            initialGenre = entry.arguments?.getString(ARG_GENRE),
                            initialLetter = entry.arguments?.getString(ARG_LETTER),
                            modifier = Modifier.fillMaxSize(),
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                        )
                    }

                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            modifier = Modifier.fillMaxSize(),
                            onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                            onResume = { animeId, episodeId, audio ->
                                navController.navigate(Routes.player(animeId, episodeId, audio))
                            },
                            onBrowse = { navController.openTopLevel(Routes.BROWSE_BASE) },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onOpenDrawer = openDrawer,
                            onOpenChangelog = { navController.navigate(Routes.CHANGELOG) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(Routes.CHANGELOG) {
                        ChangelogScreen(
                            onBack = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument(ARG_ANIME_ID) { type = NavType.IntType }),
                    ) { entry ->
                        val animeId = entry.arguments?.getInt(ARG_ANIME_ID) ?: return@composable
                        DetailScreen(
                            animeId = animeId,
                            onBack = { navController.popBackStack() },
                            onPlayEpisode = { episode, audio ->
                                navController.navigate(Routes.player(animeId, episode.id, audio))
                            },
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
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
