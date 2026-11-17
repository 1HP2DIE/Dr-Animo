package com.animo.app.data.remote

import com.animo.app.data.local.dao.AnimeStamp
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.local.entity.EpisodeEntity
import com.animo.app.data.remote.dto.AnimeDto
import com.animo.app.data.remote.dto.EpisodeDto
import com.animo.app.data.remote.dto.SeriesDataDto

/**
 * DTO -> Room row. This is the ingest boundary: every measured trap in research/DATA_CONTRACT.md §7
 * is absorbed here exactly once.
 *
 * [previous] carries forward the two columns the API does not know about (`episodeCount` and
 * `detailFetchedAt`) because a catalog page upsert rewrites the whole row.
 */
fun AnimeDto.toEntity(previous: AnimeStamp? = null): AnimeEntity {
    val genres = terms.genre.map(Normalize::cleanText).filter { it.isNotEmpty() }
    val studios = terms.studios.map(Normalize::cleanText).filter { it.isNotEmpty() }
    val producers = terms.producers.map(Normalize::cleanText).filter { it.isNotEmpty() }

    return AnimeEntity(
        id = id,
        title = Normalize.cleanText(title).ifEmpty { "Anime #$id" },
        alternative = Normalize.cleanText(alternative),
        titles = Normalize.cleanText(titles),
        native = Normalize.cleanText(native),
        slug = Normalize.slugOrFallback(slug, id),
        rating = Normalize.cleanText(rating),
        ratingNorm = Normalize.normalizeRating(rating),
        poster = poster,
        // Episode COUNT, not a boolean, and absent on 73.7% of rows -> stays null.
        isDub = isDub,
        isSub = isSub,
        description = Normalize.cleanText(description),
        aired = Normalize.cleanText(aired),
        season = Normalize.cleanText(season),
        seasonNorm = Normalize.normalizeSeason(season),
        year = year?.takeIf { it in MIN_YEAR..MAX_YEAR },
        duration = Normalize.cleanText(duration),
        status = Normalize.cleanText(status),
        statusNorm = Normalize.normalizeStatus(status),
        score = Normalize.parseDoubleOrNull(score)?.takeIf { it in 0.0..10.0 },
        malId = malId?.trim()?.takeIf { it.isNotEmpty() },
        // "" and absent both mean "unknown" — never 0.
        episodesDeclared = Normalize.parseIntOrNull(episodes)?.takeIf { it > 0 },
        episodeCount = previous?.episodeCount ?: 0,
        aniId = aniId?.trim()?.takeIf { it.isNotEmpty() },
        source = source?.trim()?.takeIf { it.isNotEmpty() },
        // s_id reaches 2,147,483,640 — Long on both sides, never Int.
        sId = sId,
        backgroundImage = backgroundImage,
        genres = Normalize.joinCsv(genres),
        studios = Normalize.joinCsv(studios),
        producers = Normalize.joinCsv(producers),
        type = terms.type.firstOrNull()?.let(Normalize::cleanText).orEmpty(),
        updatedAt = updatedAt,
        updatedAtEpoch = Normalize.parseUtcSeconds(updatedAt),
        // Frequently in the past even for "Currently Airing" -> only kept when still in the future.
        nextAirAt = Normalize.sanitizeNextAirEpoch(nextAirAt),
        nextAirEp = nextAirEp,
        detailFetchedAt = previous?.detailFetchedAt,
    )
}

/**
 * Episode DTO -> row. `title`/`jp_title` are HTML-entity escaped on the wire ("I&#39;m Luffy!").
 * `embed_url` only ever carries `sub` and `dub`; `hsub` is honoured as a sub fallback because it is
 * declared in the contract even though it was never observed live.
 */
fun EpisodeDto.toEntity(animeId: Int): EpisodeEntity = EpisodeEntity(
    episodeId = id,
    animeId = animeId,
    number = number,
    title = Normalize.cleanText(title),
    jpTitle = Normalize.cleanText(jpTitle),
    embedId = embedId,
    subUrl = (embedUrl.sub ?: embedUrl.hsub)?.trim()?.takeIf { it.isNotEmpty() },
    dubUrl = embedUrl.dub?.trim()?.takeIf { it.isNotEmpty() },
    updatedAt = updatedAt,
    updatedAtEpoch = Normalize.parseUtcSeconds(updatedAt),
)

fun SeriesDataDto.toEpisodeEntities(): List<EpisodeEntity> = episodes.map { it.toEntity(anime.id) }

private const val MIN_YEAR = 1900
private const val MAX_YEAR = 2100
