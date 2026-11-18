package com.animo.app.data.repo

import com.animo.app.data.local.dao.FavoriteDao
import com.animo.app.data.local.entity.FavoriteEntity
import com.animo.app.data.local.toDomain
import com.animo.app.domain.model.AiringDay
import com.animo.app.domain.model.AiringSchedule
import com.animo.app.domain.model.Anime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Hearted titles and the weekly airing schedule, both read from Room. */
class FavoritesRepository(private val dao: FavoriteDao) {

    // Row → model is allocation and CSV parsing; keeping it off Main matters because this flow
    // re-emits on every heart tap and every background sync write.
    fun observeFavorites(): Flow<List<Anime>> = dao.observeFavorites()
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    fun observeIsFavorite(animeId: Int): Flow<Boolean> = dao.observeIsFavorite(animeId).distinctUntilChanged()

    suspend fun setFavorite(animeId: Int, favorite: Boolean) {
        if (favorite) dao.upsert(FavoriteEntity(animeId, System.currentTimeMillis())) else dao.delete(animeId)
    }

    /**
     * The next seven days, refreshed on the database *and* on the clock.
     *
     * Without the tick the list is frozen at the moment it was read: an episode that airs while the
     * screen is open never flips to "Out", and an app left open overnight keeps calling yesterday
     * "Today".
     */
    fun observeSchedule(): Flow<List<AiringDay>> =
        combine(dao.observeAiring(), dao.observeIds(), minuteTicks()) { rows, ids, nowSec ->
            AiringSchedule.build(rows.map { it.toDomain() }, ids.toSet(), nowSec = nowSec)
        }.flowOn(Dispatchers.Default)

    /** Epoch seconds, emitted once a minute for as long as something is collecting. */
    private fun minuteTicks(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(60_000L)
        }
    }
}
