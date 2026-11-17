package com.animo.app.data.sync

import android.os.SystemClock
import com.animo.app.AppLog
import com.animo.app.data.local.dao.AnimeDao
import com.animo.app.data.remote.ApiException

/**
 * Episode lists are the expensive half of the API: backfilling all 9,404 series is **5.2 hours** of
 * requests (DATA_CONTRACT §6), so they are never bulk-fetched. Two paths use this:
 *
 *  - **on demand** — [ensure], called when a detail screen opens (1 request);
 *  - **budgeted** — [backfillMissing], called by the nightly worker inside a wall-clock window.
 */
class EpisodeBackfill(
    private val seriesFetcher: SeriesFetcher,
    private val animeDao: AnimeDao,
) {

    /**
     * Fetches only when the local copy is *known* stale: no detail pass yet, an `updated_at` newer
     * than the last detail pass, or a copy older than [STALE_AFTER_MS].
     *
     * A previous pass that returned `episodes: []` still counts as fresh — the server genuinely has
     * nothing, and re-asking on every screen open would waste the quota.
     */
    suspend fun ensure(animeId: Int): Result<Unit> {
        val fetchedAt = animeDao.detailFetchedAtOf(animeId)
        val catalogUpdatedAt = animeDao.updatedAtEpochOf(animeId) ?: 0L
        val age = fetchedAt?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE

        val fresh = fetchedAt != null &&
            age < STALE_AFTER_MS &&
            (catalogUpdatedAt <= 0L || catalogUpdatedAt * 1000L <= fetchedAt)

        if (fresh) return Result.success(Unit)
        return seriesFetcher.fetch(animeId).map { }
    }

    /** Force a refetch, ignoring the freshness window. */
    suspend fun refetch(animeId: Int): Result<Int> = seriesFetcher.fetch(animeId)

    /**
     * Spends at most [budget] requests, stopping early at [deadlineElapsedMs]
     * (`SystemClock.elapsedRealtime()` based) or as soon as the API says we are out of quota.
     *
     * @return how many series were successfully filled in.
     */
    suspend fun backfillMissing(budget: Int, deadlineElapsedMs: Long): Int {
        if (budget <= 0) return 0

        var done = 0
        val candidates = animeDao.idsAwaitingDetail(budget)

        for (animeId in candidates) {
            if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
                AppLog.w("backfill window closed after $done/${candidates.size} series")
                break
            }

            val result = seriesFetcher.fetch(animeId)
            if (result.isSuccess) {
                done++
                continue
            }

            val error = result.exceptionOrNull()
            if (error is ApiException && error.code in QUOTA_CODES) {
                AppLog.w("backfill stopped on HTTP ${error.code} after $done series")
                break
            }
        }

        AppLog.i("backfill filled $done/${candidates.size} series")
        return done
    }

    /** Counts series that still have no detail pass — cheap, used for worker logging. */
    suspend fun pendingCount(limit: Int = 1): Int = animeDao.idsAwaitingDetail(limit).size

    private companion object {
        /** 6 h: a detail screen open after that revalidates even if `updated_at` did not move. */
        const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        val QUOTA_CODES = setOf(403, 429)
    }
}
