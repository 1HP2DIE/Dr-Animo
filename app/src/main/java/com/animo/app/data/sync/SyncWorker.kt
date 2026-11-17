package com.animo.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.animo.app.AppLog
import com.animo.app.di.ServiceLocator

/**
 * Nightly catalog refresh + a budgeted episode backfill.
 *
 * Time budget: WorkManager kills a worker after ~10 minutes. Measured cost is ~210 s for the 95
 * catalog pages plus up to 60 detail calls (~132 s), and the backfill window is closed at 8 minutes
 * so the process finishes well inside the limit.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.install(applicationContext)

        AppLog.i("nightly sync started (attempt " + (runAttemptCount + 1) + ")")

        val catalog = ServiceLocator.animeRepository.refreshCatalog(force = false)
        if (catalog.isFailure) {
            val error = catalog.exceptionOrNull()
            AppLog.w("nightly catalog sync failed: " + (error?.message ?: "unknown"), error)
            return if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + BACKFILL_WINDOW_MS
        val filled = ServiceLocator.episodeBackfill.backfillMissing(BACKFILL_BUDGET, deadline)

        AppLog.i("nightly sync finished: $filled series backfilled")
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 8 minutes leaves room for the catalog pass inside WorkManager's ~10 min ceiling. */
        const val BACKFILL_WINDOW_MS = 8 * 60 * 1000L

        /** 500/night was the research estimate (≈18 min) — capped to what a worker can finish. */
        const val BACKFILL_BUDGET = 150
    }
}
