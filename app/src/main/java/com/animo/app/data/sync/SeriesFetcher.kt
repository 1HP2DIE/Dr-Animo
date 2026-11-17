package com.animo.app.data.sync

import androidx.room.withTransaction
import com.animo.app.AppLog
import com.animo.app.data.local.AnimoDatabase
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.EpisodeDao
import com.animo.app.data.remote.AnikotoApi
import com.animo.app.data.remote.toEntity
import com.animo.app.data.remote.toEpisodeEntities

/**
 * One `/series/{id}` round trip, applied atomically.
 *
 * Used by both the catalog delta pass and the on-demand backfill so there is exactly one place that
 * knows how a detail response lands in Room.
 */
class SeriesFetcher(
    private val api: AnikotoApi,
    private val db: AnimoDatabase,
    private val animeDao: AnimeDao,
    private val episodeDao: EpisodeDao,
) {

    /** @return the episode count now stored for [animeId]. */
    suspend fun fetch(animeId: Int): Result<Int> {
        val detail = api.series(animeId).getOrElse { error ->
            return Result.failure(error)
        }

        if (detail.anime.id != animeId) {
            // Never observed, but an id mismatch would write episodes under the wrong parent.
            AppLog.w("series $animeId answered with id=${detail.anime.id}; using the requested id")
        }

        val fetchedAt = System.currentTimeMillis()
        val previous = animeDao.stampsFor(listOf(animeId)).firstOrNull()
        val episodes = detail.toEpisodeEntities()
        val anime = detail.anime.toEntity(previous).copy(
            id = animeId,
            detailFetchedAt = fetchedAt,
        )

        val staleIds = if (episodes.isEmpty()) {
            emptyList()
        } else {
            val fetchedIds = episodes.asSequence().map { it.episodeId }.toSet()
            episodeDao.listForAnime(animeId).asSequence()
                .map { it.episodeId }
                .filter { it !in fetchedIds }
                .toList()
        }

        val count = db.withTransaction {
            animeDao.upsert(anime)
            if (episodes.isEmpty()) {
                // 7/40 series legitimately answer `episodes: []` with HTTP 200 — keep what we have.
                episodeDao.countForAnime(animeId)
            } else {
                episodeDao.upsertAll(episodes)
                staleIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                    episodeDao.deleteByIds(chunk)
                }
                episodes.size
            }
        }

        animeDao.updateDetailMeta(animeId, count, fetchedAt)
        return Result.success(count)
    }

    private companion object {
        /** SQLite's bound-variable ceiling is 999 on API 24-era platforms; stay well under it. */
        const val SQLITE_BIND_CHUNK = 400
    }
}
