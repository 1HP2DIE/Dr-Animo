package com.animo.app.data.sync

import com.animo.app.data.local.dao.SlugStamp
import com.animo.app.data.local.entity.AnimeEntity
import com.animo.app.data.remote.provider.movielink.MovieLinkCard
import com.animo.app.data.remote.provider.movielink.MovieLinkCatalogue
import com.animo.app.data.remote.provider.movielink.MovieLinkParser
import com.animo.app.domain.model.CatalogIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * Pulls the MovieLink listing into the `anime` table, a bounded number of pages at a time.
 *
 * ## Why bounded, and why a cursor
 * The index is 529 pages of 30 (~15,900 titles). Walking it in one go is ~9 minutes of paced
 * requests on a phone — longer than a worker slot and long enough that the user closes the app
 * mid-run. So one call walks at most [maxPages] pages (25 by default, ~30 s at the site's own 1 s
 * cadence) and stores where it stopped. The next call continues from there; when the walk reaches
 * the last page it wraps to page 1, because the listing is newest-first and the head is what
 * changes. `force` restarts at page 1 (an explicit "sync now"), still bounded by [maxPages].
 *
 * ## What a page write may touch
 * - **Only its own rows.** Ids come from [CatalogIds.forSlug], which is inside the MovieLink band,
 *   so neither the positive original catalogue nor the two Hindi bands can be addressed at all.
 * - **Nothing is deleted.** A title that leaves the listing keeps its row; the detail screen and
 *   favourites still work, and a re-listing upserts over the same id.
 * - **A hash collision loses one card, not a row.** Two slugs mapping onto one id are
 *   indistinguishable by id, so an existing row whose `slug` differs is left alone and the incoming
 *   card is skipped (first writer wins). Without that check the two titles would overwrite each
 *   other on every sync.
 *
 * ## Failure shape
 * One request per page, and the provider's own pacing is the only rate limit. A first-page failure
 * is a failed run (nothing was written, the cursor did not move). A later page failure stops the
 * walk and returns **success** with what was written: the cursor sits on the last committed page, so
 * the next run retries exactly the failed page. Cancellation is rethrown untouched — never converted
 * into a failure — and the cursor is never advanced past a page that was not written.
 *
 * @param maxPages pages per call. Clamped to at least 1 and to the catalogue length.
 * @param now epoch **seconds**, the unit `updatedAtEpoch` carries; injectable for tests.
 * @param log one-line trace sink, no `android.util.Log` so the class runs on the JVM.
 */
class MovieLinkSyncer(
    private val catalogue: MovieLinkCatalogue,
    private val store: MovieLinkStore,
    private val cursorStore: MovieLinkCursorStore,
    private val maxPages: Int = DEFAULT_PAGES_PER_RUN,
    private val now: () -> Long = { System.currentTimeMillis() / 1000L },
    private val log: (String) -> Unit = {},
) {

    /** A second concurrent walk would write the same cursor from two places; refuse instead. */
    private val mutex = Mutex()

    /**
     * Walks up to [maxPages] listing pages and upserts their cards.
     *
     * @param force ignore the stored cursor and restart at page 1.
     * @param onProgress `(pages done this run, pages planned for this run)` — 1-based, monotone, and
     *   emitted only after a page's rows are committed, so it can never run ahead of the database.
     * @return the number of rows written, or the failure that stopped the run before its first page.
     */
    suspend fun sync(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        if (!catalogue.isConfigured) {
            return Result.failure(IllegalStateException("MovieLink index host is not configured"))
        }
        if (!mutex.tryLock()) {
            return Result.failure(IllegalStateException("movielink sync already running"))
        }

        try {
            return walk(force, onProgress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("movielink sync aborted: ${error.message}")
            return Result.failure(error)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun walk(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result<Int> {
        val start = if (force) 1 else cursorStore.read().nextPage
        val planned = minOf(maxPages.coerceAtLeast(1), MovieLinkParser.MAX_PAGE - start + 1)

        var written = 0
        var done = 0
        var lastPage = start - 1

        for (page in start until start + planned) {
            currentCoroutineContext().ensureActive()

            val parsed = catalogue.cataloguePage(page).getOrElse { error ->
                if (done == 0) {
                    log("movielink page $page failed before anything was written: ${error.message}")
                    return Result.failure(error)
                }
                log("movielink page $page failed after $done page(s) — resuming there next run")
                return Result.success(written)
            }

            written += writePage(parsed.cards)
            done += 1
            lastPage = page

            // A page with no cards is the only reliable end signal: `hasNext` comes from the pager
            // markup, which a restyle silently empties, and a walk that trusted it would stop on
            // page 1 forever. Reaching the catalogue end wraps the cursor to page 1.
            if (parsed.isEmpty) {
                log("movielink page $page is empty — catalogue ended, cursor wraps to page 1")
                lastPage = MovieLinkParser.MAX_PAGE
            }

            persist(lastPage)
            emit(onProgress, done, planned)

            if (parsed.isEmpty) break
            if (!parsed.hasNext) log("movielink page $page offered no next link; continuing this run")
        }

        log("movielink sync: $written rows over $done page(s) from page $start")
        return Result.success(written)
    }

    // ------------------------------------------------------------------ writing

    /**
     * One page in one upsert, with the two guards a hash-keyed catalogue needs.
     *
     * Cards are filtered before any database work: an unhashable page path or an id outside the band
     * is a parse/mirror problem, and the honest response is to drop that card, not the page.
     */
    private suspend fun writePage(cards: List<MovieLinkCard>): Int {
        if (cards.isEmpty()) return 0

        val candidates = ArrayList<Candidate>(cards.size)
        for (card in cards) {
            val slug = MovieLinkRows.slugOf(card)
            if (slug.isEmpty()) {
                log("movielink: card '${card.seriesId}' has no slug — skipped")
                continue
            }
            val id = runCatching { CatalogIds.forSlug(card.seriesId) }.getOrNull() ?: continue
            if (!CatalogIds.isMovieLink(id)) {
                log("movielink: card '${card.seriesId}' does not map into the id band — skipped")
                continue
            }
            candidates += Candidate(id = id, slug = slug, card = card)
        }
        if (candidates.isEmpty()) return 0

        val stored: Map<Int, SlugStamp> =
            store.slugStamps(candidates.map { it.id }.distinct()).associateBy { it.id }

        val at = now()
        val rows = ArrayList<AnimeEntity>(candidates.size)
        val claimed = HashSet<Int>(candidates.size)

        for (candidate in candidates) {
            if (!claimed.add(candidate.id)) {
                log("movielink: '${candidate.slug}' collides with another card on this page — skipped")
                continue
            }

            val previous = stored[candidate.id]
            if (previous != null && previous.slug != candidate.slug) {
                // First writer wins: the row already belongs to a different page that hashed here.
                log(
                    "movielink: hash collision at ${candidate.id} " +
                        "('${candidate.slug}' vs stored '${previous.slug}') — kept the stored row",
                )
                continue
            }

            // try/catch, not runCatching: `continue` inside a lambda is still an experimental
            // feature of the language, and this loop has to skip exactly one card.
            val row = try {
                MovieLinkRows.row(
                    card = candidate.card,
                    previous = previous,
                    poster = catalogue.posterUrl(candidate.card.poster),
                    now = at,
                )
            } catch (error: Exception) {
                log("movielink: '${candidate.slug}' is unmappable: ${error.message}")
                continue
            }
            rows += row
        }

        if (rows.isEmpty()) return 0
        return upsert(rows)
    }

    /**
     * [MovieLinkStore.upsertAll] for the page, falling back to one row at a time.
     *
     * The whole-page statement is the normal path (one transaction, one FTS trigger batch). The
     * fallback exists because `anime.slug` is unique across every catalogue: a single conflict — a
     * collision with a row from a source this band cannot see — must cost one row, not the page and
     * not the rest of the walk.
     */
    private suspend fun upsert(rows: List<AnimeEntity>): Int =
        try {
            store.upsertAll(rows)
            rows.size
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("movielink: page upsert failed (${error.message}) — retrying row by row")
            var written = 0
            for (row in rows) {
                try {
                    store.upsertAll(listOf(row))
                    written += 1
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (rowError: Throwable) {
                    log("movielink: row ${row.id} ('${row.slug}') rejected: ${rowError.message}")
                }
            }
            written
        }

    private suspend fun persist(lastPage: Int) {
        try {
            cursorStore.write(MovieLinkCursor(lastPage = lastPage, lastSyncEpoch = now()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // A lost cursor costs repeated (idempotent) pages, never data — do not abort the walk.
            log("movielink: could not persist cursor at page $lastPage: ${error.message}")
        }
    }

    private suspend fun emit(
        onProgress: suspend (done: Int, total: Int) -> Unit,
        done: Int,
        total: Int,
    ) {
        try {
            onProgress(done, total)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log("movielink: progress callback threw: ${error.message}")
        }
    }

    private data class Candidate(val id: Int, val slug: String, val card: MovieLinkCard)

    companion object {
        /**
         * Pages per run. 25 pages × 30 cards ≈ 750 titles ≈ 30 s at the measured 1 s cadence — the
         * longest walk that still fits a phone's patience; a full catalogue is 22 such runs.
         */
        const val DEFAULT_PAGES_PER_RUN = 25

        /** Highest page the walk will ever request; the provider clamps to the same value. */
        val MAX_PAGES: Int get() = MovieLinkParser.MAX_PAGE
    }
}
