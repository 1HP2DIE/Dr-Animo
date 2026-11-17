package com.animo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.animo.app.domain.model.AudioTrack
import com.animo.app.ui.detail.DetailScreen
import com.animo.app.ui.home.HomeScreen
import com.animo.app.ui.player.PlayerScreen
import com.animo.app.ui.search.SearchScreen

/**
 * String routes (navigation-compose 2.8.5). Kept as a single object so call sites never hand-build
 * a path and the argument names live next to the route templates.
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DETAIL = "detail/{$ARG_ANIME_ID}"
    const val PLAYER = "player/{$ARG_ANIME_ID}/{$ARG_EPISODE_ID}/{$ARG_AUDIO}"

    fun detail(animeId: Int): String = "detail/$animeId"

    fun player(animeId: Int, episodeId: Long, audio: AudioTrack): String =
        "player/$animeId/$episodeId/${audio.name}"
}

const val ARG_ANIME_ID = "animeId"
const val ARG_EPISODE_ID = "episodeId"
const val ARG_AUDIO = "audio"

@Composable
fun AnimoNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.HOME,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenAnime = { animeId -> navController.navigate(Routes.detail(animeId)) },
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
