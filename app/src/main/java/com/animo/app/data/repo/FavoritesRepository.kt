package com.animo.app.data.repo

import com.animo.app.data.local.dao.FavoriteDao
import com.animo.app.data.local.entity.FavoriteEntity
import com.animo.app.data.local.toDomain
import com.animo.app.domain.model.AiringDay
import com.animo.app.domain.model.AiringSchedule
import com.animo.app.domain.model.Anime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Hearted titles and the weekly airing schedule, both read from Room. */
class FavoritesRepository(private val dao: FavoriteDao) {

    fun observeFavorites(): Flow<List<Anime>> = dao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    fun observeIsFavorite(animeId: Int): Flow<Boolean> = dao.observeIsFavorite(animeId).distinctUntilChanged()

    suspend fun setFavorite(animeId: Int, favorite: Boolean) {
        if (favorite) dao.upsert(FavoriteEntity(animeId, System.currentTimeMillis())) else dao.delete(animeId)
    }

    fun observeSchedule(): Flow<List<AiringDay>> =
        combine(dao.observeAiring(), dao.observeIds()) { rows, ids ->
            AiringSchedule.build(rows.map { it.toDomain() }, ids.toSet())
        }
}
