package com.animo.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.animo.app.AppLog
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.Providers
import kotlinx.coroutines.CancellationException

/**
 * Nightly housekeeping, cheap by design.
 *
 * Two stages, both budgeted:
 *  1. a **provider fan-out** — every catalogue the viewer left on `SyncMode.AUTO` runs its own cheap
 *     delta walk through [ProviderSync]. Normally that is 1-3 pages each, because `/recent-anime` is
 *     newest-first and the walk stops as soon as the tail is provably unchanged. The set is read from
 *     the preference table, not compiled in, and one provider's failure is logged and skipped rather
 *     than fatal to the others;
 *  2. the **episode queue** — only when the original catalogue was among the providers that ran, and
 *     only for titles [SyncPolicy] says are due (airing shows weekly, finished shows never once their
 *     episode list is complete, gaps first). It is gated on the original catalogue on purpose: a
 *     viewer who switched that catalogue to `MANUAL` or `OFF` did not ask for its episodes to be
 *     fetched at night either.
 *
 * Wall clock: measured ~7 s for a delta pass and ~2.2 s per queued title, against WorkManager's
 * ~10-minute ceiling. The deadline check is still there because an unusually large delta (a fresh
 * install, or a catalogue reshuffle) can queue hundreds of titles.
 *
 * Retry policy is unchanged: any tick where **every** provider that ran failed is retried with
 * backoff, up to [MAX_ATTEMPTS]; a partial success is a success, because the next tick picks up the
 * one that failed and `ExistingPeriodicWorkPolicy.KEEP` keeps it on schedule.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.install(applicationContext)

        AppLog.i("nightly sync started (attempt " + (runAttemptCount + 1) + ")")

        val providerSync = ServiceLocator.providerSync
        val providerIds = try {
            providerSync.autoProviderIds()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // The preference table is what decides *whether* to fetch; without it the honest move is
            // another tick, not a night of silence.
            AppLog.w(
                "nightly sync: provider preferences unreadable (${error.javaClass.simpleName})",
            )
            return retryOrFail()
        }

        if (providerIds.isEmpty()) {
            AppLog.i("nightly sync finished: no provider is set to auto")
            return Result.success()
        }

        val synced = ArrayList<String>(providerIds.size)
        for (providerId in providerIds) {
            // `force = false` is the whole point: this is the cheap delta walk.
            val result = providerSync.sync(providerId, force = false) { _, _ -> }
            if (result.isSuccess) {
                synced += providerId
                AppLog.d("nightly sync: $providerId ok (${result.getOrNull() ?: 0} rows)")
            } else {
                val reason = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
                AppLog.w("nightly sync: $providerId failed ($reason)")
            }
        }

        if (synced.isEmpty()) {
            AppLog.w("nightly sync: every provider failed (${providerIds.size} attempted)")
            return retryOrFail()
        }

        if (Providers.ORIGINAL !in synced) {
            AppLog.i(
                "nightly sync finished: ${synced.size}/${providerIds.size} providers synced, " +
                    "episode queue skipped",
            )
            return Result.success()
        }

        val backfill = ServiceLocator.episodeBackfill
        backfill.refreshAired()
        val due = backfill.dueCount()
        if (due == 0) {
            AppLog.i(
                "nightly sync finished: ${synced.size}/${providerIds.size} providers synced, " +
                    "nothing due",
            )
            return Result.success()
        }

        val deadline = android.os.SystemClock.elapsedRealtime() + QUEUE_WINDOW_MS
        val refreshed = backfill.refreshDue(QUEUE_BUDGET, deadline)

        AppLog.i(
            "nightly sync finished: ${synced.size}/${providerIds.size} providers synced, " +
                "$refreshed/$due due series refreshed",
        )
        return Result.success()
    }

    /** Same shape as before the fan-out: give the tick another go, then admit defeat. */
    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 8 minutes leaves room for the delta passes inside WorkManager's ~10 min ceiling. */
        const val QUEUE_WINDOW_MS = 8 * 60 * 1000L

        /**
         * A normal night is ~30 (airing titles due this week), so this ceiling is only reached on a
         * first run or after a long absence.
         */
        const val QUEUE_BUDGET = 240
    }
}
