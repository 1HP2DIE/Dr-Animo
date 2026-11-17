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
 * not reset the schedule (a user who opens the app daily would otherwise never get a nightly run).
 */
object SyncScheduler {

    const val WORK_NAME = "animo-catalog-sync"
    const val TAG = "sync"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
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

        AppLog.d("catalog sync scheduled (daily, network-constrained)")
    }

    private const val INITIAL_DELAY_MINUTES = 30L
}
