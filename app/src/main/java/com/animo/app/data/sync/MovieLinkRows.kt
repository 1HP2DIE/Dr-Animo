package com.animo.app.data.sync

import com.animo.app.data.local.dao.SlugStamp
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.remote.Normalize
import com.animo.app.data.remote.provider.movielink.MovieLinkCard
import com.animo.app.data.remote.provider.movielink.MovieLinkKind
import com.animo.app.domain.model.CatalogIds

/**
 * MovieLink listing card → `anime` row.
 *
 * Kept out of [MovieLinkSyncer] so the mapping is a pure function and every rule below is testable
 * against the real fixture without a database, a coroutine or a network.
 *
 * ## What a card can and cannot fill
 * A listing card carries a page path, a title, sometimes a year, a poster and an `[Action,Thriller]`
 * tag block. It carries **no** storyline, status, score or episode count. Every absent column is
 * written as absent (`""`, `null`, `0`) instead of a plausible default: `statusNorm =
 * "finished_airing"` for a title that is still running is a lie the filter sheet would then offer,
 * and a guessed year poisons the year facet. `description` stays empty until a detail refresh — the
 * syncer is deliberately one request per listing page, never one per title.
 *
 * ## What is carried forward
 * [previous] is the stored row's state. `detailFetchedAt` and `episodeCount` are the detail pass's
 * property (it is what [com.animo.app.data.local.dao.AnimeDao.updateDetailMeta] writes), so a
 * listing upsert — which rewrites the whole row — must not reset them. Same rule the original
 * ingest and the Hindi catalogues follow.
 */
internal object MovieLinkRows {

    /** Value of `anime.source` for this catalogue. Never blank, so a row says where it came from. */
    const val SOURCE = "movielink"

    private const val TYPE_MOVIE = "Movie"
    private const val TYPE_TV = "TV"

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100

    /**
     * The opaque slug: the last path segment of `"movie/mKs_abc"`.
     *
     * This is what `anime.slug` stores (it has a unique index across every catalogue) and what
     * [CatalogIds.forSlug] hashes, so id and slug can never disagree about which page a row is.
     */
    fun slugOf(card: MovieLinkCard): String =
        card.seriesId.trim().trim('/').substringAfterLast('/')


    /**
     * One row. [poster] is already absolute (the caller resolves it against the live mirror);
     * [previous] is the stored state for this id, or `null` for a new title.
     */
    fun row(
        card: MovieLinkCard,
        previous: SlugStamp?,
        poster: String,
        now: Long,
    ): AnimeEntity {
        val slug = slugOf(card)
        val id = CatalogIds.forSlug(card.seriesId)
        val title = Normalize.cleanText(card.title).ifEmpty { slug }
        val kind = if (card.kind == MovieLinkKind.MOVIE) TYPE_MOVIE else TYPE_TV

        // A film is one playable entry; a series/drama/anime prints its grid on the detail page, so
        // the only honest answer here is "whatever the detail pass already learned".
        val listedEpisodes = if (card.kind == MovieLinkKind.MOVIE) 1 else 0
        val episodeCount = maxOf(previous?.episodeCount ?: 0, listedEpisodes)

        return AnimeEntity(
            id = id,
            title = title,
            alternative = "",
            titles = "",
            native = "",
            // Unique index: the bare opaque slug is already unique per page on this index.
            slug = slug,
            rating = "",
            ratingNorm = "",
            poster = poster,
            // Neither flag: `isDub` is the Hindi-dub filter's input, and this catalogue publishes a
            // single (original) audio track. `hasSub` already reads true through `episodeCount`.
            isDub = null,
            isSub = null,
            // Empty on purpose — the listing has no storyline, and the detail refresh owns it.
            description = "",
            aired = "",
            season = "",
            seasonNorm = "",
            year = card.year?.takeIf { it in MIN_YEAR..MAX_YEAR },
            duration = "",
            status = "",
            statusNorm = "",
            score = null,
            malId = null,
            episodesDeclared = null,
            episodeCount = episodeCount,
            aniId = null,
            source = SOURCE,
            sId = 0L,
            backgroundImage = "",
            genres = Normalize.joinCsv(card.tags),
            studios = "",
            producers = "",
            type = Normalize.normalizeType(kind) ?: kind,
            updatedAt = "",
            updatedAtEpoch = now,
            nextAirAt = null,
            nextAirEp = null,
            detailFetchedAt = previous?.detailFetchedAt,
            // Host-relative on purpose: the index rotates mirrors, so the path is the durable key
            // and `MovieLinkProvider.detail` rebuilds the URL from the current host (and discards
            // the host part if a stored value is absolute).
            originUrl = card.seriesId.trim().trim('/'),
        )
    }
}
