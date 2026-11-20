package com.animo.app.data.local

import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.ContentKind
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.Providers

/**
 * Room row -> domain model. The domain model is what every UI screen and the player see; column
 * names, CSV splitting and normalised enums stop here.
 */
fun AnimeEntity.toDomain(): Anime {
    // The owner column only exists from v6 on. A row written before it (or by a mapper that predates
    // it) falls back to the id band — the same rule [Providers.idFor] documents — so a read path can
    // always name the catalogue instead of handing the UI an empty string.
    val owner = providerId.ifBlank { Providers.idFor(id) }

    return Anime(
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
        originUrl = originUrl,
        providerId = owner,
        // `ANIME` is the column default, i.e. also what a row with no readable value means; a blank
        // one (only reachable if a writer passed "") is inferred from the owner and format instead
        // of being reported as an anime — the same inference the v5->v6 migration applied.
        contentKind = if (contentKind.isBlank()) {
            ContentKind.infer(owner, type, episodeCount)
        } else {
            ContentKind.fromStored(contentKind)
        },
        language = language,
    )
}

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
