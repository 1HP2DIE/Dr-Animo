package com.animo.app.data.local

import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.Episode

/**
 * Room row -> domain model. The domain model is what every UI screen and the player see; column
 * names, CSV splitting and normalised enums stop here.
 */
fun AnimeEntity.toDomain(): Anime = Anime(
    id = id,
    title = title,
    alternative = alternative,
    titles = titles,
    native = native,
    slug = slug,
    rating = rating,
    poster = poster,
    background = backgroundImage,
    description = description,
    score = score,
    year = year,
    season = seasonNorm.ifBlank { "unknown" },
    status = status,
    type = type,
    genres = Normalize.splitCsv(genres),
    studios = Normalize.splitCsv(studios),
    episodesDeclared = episodesDeclared,
    episodeCount = episodeCount,
    subCount = isSub,
    dubCount = isDub,
    malId = malId,
    aniId = aniId,
    updatedAtEpoch = updatedAtEpoch,
    nextAirAt = nextAirAt,
    nextAirEp = nextAirEp,
)

fun EpisodeEntity.toDomain(): Episode = Episode(
    id = episodeId,
    animeId = animeId,
    number = number,
    title = title,
    embedId = embedId,
    subUrl = subUrl,
    dubUrl = dubUrl,
    updatedAtEpoch = updatedAtEpoch,
)
