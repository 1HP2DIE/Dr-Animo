package com.animo.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.animo.app.AppLog
import com.animo.app.di.ServiceLocator

/**
 * Nightly housekeeping, cheap by design.
 *
 * Two stages, both budgeted:
 *  1. a catalog **delta** walk — normally 1-3 pages because `/recent-anime` is newest-first and the
 *     walk stops as soon as the tail is provably unchanged;
 *  2. the **episode queue** — only titles [SyncPolicy] says are due (airing shows weekly, finished
 *     shows never once their episode list is complete, gaps first).
 *
 * Wall clock: measured ~7 s for a delta pass and ~2.2 s per queued title, against WorkManager's
 * ~10-minute ceiling. The deadline check is still there because an unusually large delta (a fresh
 * install, or a catalogue reshuffle) can queue hundreds of titles.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.install(applicationContext)

        AppLog.i("nightly sync started (attempt " + (runAttemptCount + 1) + ")")

        // `force = false` is the whole point: this is the cheap delta walk.
        val catalog = ServiceLocator.animeRepository.refreshCatalog(force = false)
        if (catalog.isFailure) {
            val error = catalog.exceptionOrNull()
            AppLog.w("nightly catalog sync failed: " + (error?.message ?: "unknown"), error)
            return if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

        val backfill = ServiceLocator.episodeBackfill
        backfill.refreshAired()
        val due = backfill.dueCount()
        if (due == 0) {
            AppLog.i("nightly sync finished: nothing due")
            return Result.success()
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + QUEUE_WINDOW_MS
        val refreshed = backfill.refreshDue(QUEUE_BUDGET, deadline)

        AppLog.i("nightly sync finished: $refreshed/$due due series refreshed")
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 8 minutes leaves room for the delta pass inside WorkManager's ~10 min ceiling. */
        const val QUEUE_WINDOW_MS = 8 * 60 * 1000L

        /**
         * A normal night is ~30 (airing titles due this week), so this ceiling is only reached on a
         * first run or after a long absence.
         */
        const val QUEUE_BUDGET = 240
    }
}
