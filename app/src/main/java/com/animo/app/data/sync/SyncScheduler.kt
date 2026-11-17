package com.animo.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.animo.app.AppLog
import java.util.concurrent.TimeUnit

/**
 * Owns the periodic refresh registration. `KEEP` semantics: re-registering on every cold start must
 * not reset the schedule (a user who opens the app daily would otherwise never get a run).
 *
 * Six hours, not one day: the job is a **delta** walk now (1-3 pages, ~7 s, ~12 requests/day total),
 * so running it four times a day costs less than the old once-a-day full walk and keeps "Recently
 * updated" genuinely recent. The expensive half — episode lists — is gated by [SyncPolicy], which
 * only lets airing titles through weekly and finished titles never.
 */
object SyncScheduler {

    const val WORK_NAME = "animo-catalog-sync"
    const val TAG = "sync"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )

        AppLog.d("catalog sync scheduled (every $SYNC_INTERVAL_HOURS h, network-constrained)")
    }

    /** Four cheap delta passes a day (~12 requests) beat one 95-page walk. */
    private const val SYNC_INTERVAL_HOURS = 6L

    private const val INITIAL_DELAY_MINUTES = 30L
}
