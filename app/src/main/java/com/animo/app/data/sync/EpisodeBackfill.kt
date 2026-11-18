package com.animo.app.data.sync

import android.os.SystemClock
import com.animo.app.AppLog
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.local.dao.FavoriteDao
import com.animo.app.data.remote.ApiException

/**
 * Episode lists are the expensive half of the API: backfilling all 9,404 series is **5.2 hours** of
 * requests (DATA_CONTRACT §6), so they are never bulk-fetched. Three paths use this class:
 *
 *  - **on demand** — [ensure], called when a detail screen opens (at most 1 request, and 0 for a
 *    finished title whose list is already complete);
 *  - **budgeted queue** — [refreshDue], called by the nightly worker; it only ever looks at titles
 *    [SyncPolicy] says are due, so a normal night is a handful of requests instead of 150 blind ones;
 *  - **forced** — [refetch], for the Refresh button on a detail screen.
 */
class EpisodeBackfill(
    private val seriesFetcher: SeriesFetcher,
    private val animeDao: AnimeDao,
    private val favoriteDao: FavoriteDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The silent per-title sync: refreshes airing titles whose recorded air time has passed since
     * their last fetch (a new episode is out), favourites first, then any hearted title not seen
     * for a day. Small and cheap, so it runs on every app start as well as in the periodic job.
     *
     * @return how many series were refreshed.
     */
    suspend fun refreshAired(budget: Int = AIRED_BUDGET): Int {
        val dao = favoriteDao ?: return 0
        val current = now()
        val ids = (dao.idsAiredSinceFetch(current, budget) + dao.favoriteIdsDue(current - FAVORITE_INTERVAL_MS, budget))
            .distinct()
            .take(budget)
        if (ids.isEmpty()) return 0
        var done = 0
        for (animeId in ids) {
            val result = seriesFetcher.fetch(animeId)
            if (result.isSuccess) {
                done++
                continue
            }
            val error = result.exceptionOrNull()
            if (error is ApiException && error.code in QUOTA_CODES) break
        }
        AppLog.i("silent sync refreshed $done/${ids.size} aired or favourite series")
        return done
    }

    /**
     * Fetches only when [SyncPolicy] says the row is due, or when the catalog row itself moved after
     * our last detail pass (the delta pass is the only other thing allowed to invalidate a title).
     *
     * The policy check is what stops a finished show from being re-fetched on every detail open:
     * before this, any row older than 6 h was revalidated, so browsing ten old series meant ten
     * pointless requests for data that can never change.
     */
    suspend fun ensure(animeId: Int): Result<Unit> {
        val state = animeDao.syncStateOf(animeId)
            ?: return seriesFetcher.fetch(animeId).map { }

        val fetchedAt = state.detailFetchedAt
        val current = now()

        // On-demand is allowed to fetch a finished title the background never touches: the user is
        // looking at it, so a never-detailed row is worth one request even if it finished in 2014.
        val due = SyncPolicy.shouldFetchOnOpen(
            statusNorm = state.statusNorm,
            episodesDeclared = state.episodesDeclared,
            episodeCount = state.episodeCount,
            detailFetchedAt = fetchedAt,
            now = current,
        )

        // A catalog delta can move `updatedAt` for a title we already detailed (a new episode was
        // published). That invalidates the local copy even when the policy says it is not due.
        val catalogMoved = fetchedAt != null &&
            (animeDao.updatedAtEpochOf(animeId) ?: 0L) * 1000L > fetchedAt

        if (!due && !catalogMoved) return Result.success(Unit)
        return seriesFetcher.fetch(animeId).map { }
    }

    /** Force a refetch, ignoring the freshness window. */
    suspend fun refetch(animeId: Int): Result<Int> = seriesFetcher.fetch(animeId)

    /**
     * Spends at most [budget] requests on titles that are actually due, stopping early at
     * [deadlineElapsedMs] (`SystemClock.elapsedRealtime()` based) or as soon as the API says we are
     * out of quota.
     *
     * @return how many series were successfully refreshed.
     */
    suspend fun refreshDue(budget: Int, deadlineElapsedMs: Long): Int {
        if (budget <= 0) return 0

        val current = now()
        val candidates = animeDao.idsDueForEpisodeSync(
            ongoingCutoff = current - SyncPolicy.ONGOING_INTERVAL_MS,
            gapCutoff = current - SyncPolicy.GAP_INTERVAL_MS,
            unknownCutoff = current - SyncPolicy.UNKNOWN_RECHECK_MS,
            newRowCutoff = current - SyncPolicy.NEW_ROW_WINDOW_MS,
            limit = budget,
        )
        if (candidates.isEmpty()) {
            AppLog.d("episode queue: nothing due")
            return 0
        }

        var done = 0
        for (animeId in candidates) {
            if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                AppLog.w("episode queue closed after $done/${candidates.size} series")
                break
            }

            val result = seriesFetcher.fetch(animeId)
            if (result.isSuccess) {
                done++
                continue
            }

            val error = result.exceptionOrNull()
            if (error is ApiException && error.code in QUOTA_CODES) {
                AppLog.w("episode queue stopped on HTTP ${error.code} after $done series")
                break
            }
        }

        AppLog.i("episode queue refreshed $done/${candidates.size} due series")
        return done
    }

    /** How many titles are due right now — cheap, used by settings and by the worker's logging. */
    suspend fun dueCount(): Int {
        val current = now()
        return animeDao.countDueForEpisodeSync(
            ongoingCutoff = current - SyncPolicy.ONGOING_INTERVAL_MS,
            gapCutoff = current - SyncPolicy.GAP_INTERVAL_MS,
            unknownCutoff = current - SyncPolicy.UNKNOWN_RECHECK_MS,
        )
    }

    /** Titles whose declared episode count exceeds what is stored (the visible "missing episodes"). */
    suspend fun missingEpisodeTitles(): Int = animeDao.countEpisodeGaps()

    private companion object {
        val QUOTA_CODES = setOf(403, 429)
        const val AIRED_BUDGET = 24
        const val FAVORITE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
