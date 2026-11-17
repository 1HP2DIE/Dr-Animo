package com.animo.app.data.remote

import android.os.SystemClock
import okhttp3.Response
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises every outbound catalog request and enforces the measured quota.
 *
 * Measured facts (research/DATA_CONTRACT.md §1): 60 requests / 120 s per IP = 0.5 req/s sustained.
 * A 1.2 s cadence trips the limiter at request 61, so the floor here is 2.2 s between request
 * starts. Holding the mutex across the delay is intentional: it is what makes the spacing real
 * instead of advisory, because a `Mutex` is not reentrant and all callers queue behind it.
 */
internal class RequestGate(
    private val onEvent: (ApiEvent) -> Unit,
) {

    private val mutex = Mutex()

    private var lastStartedAt = 0L
    private var penaltyUntil = 0L

    @Volatile
    private var remaining: Int = Int.MAX_VALUE

    @Volatile
    private var resetAtEpochSeconds: Long = 0L

    /** Suspends until this caller is allowed to fire, then stamps the request start. */
    suspend fun acquire() {
        mutex.withLock {
            val intervalWait = (lastStartedAt + MIN_INTERVAL_MS) - SystemClock.elapsedRealtime()
            if (intervalWait > 0) delay(intervalWait)

            if (remaining < LOW_QUOTA_FLOOR) {
                val untilReset = (resetAtEpochSeconds * 1000L) - System.currentTimeMillis()
                val wait = untilReset.coerceIn(MIN_PARK_MS, MAX_PARK_MS)
                onEvent(ApiEvent.LowQuota(remaining, wait))
                delay(wait)
                // Optimistic until the next response tells us otherwise, otherwise every following
                // request would park for another 90 s and the sync could never make progress.
                remaining = Int.MAX_VALUE
            }

            val penaltyWait = penaltyUntil - SystemClock.elapsedRealtime()
            if (penaltyWait > 0) {
                onEvent(ApiEvent.Throttled(penaltyWait))
                delay(penaltyWait)
            }

            lastStartedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Records the quota headers of a completed response, including non-2xx ones. */
    fun onResponse(response: Response) {
        val quota = response.header(HEADER_REMAINING)?.trim()?.toIntOrNull()
        if (quota != null) remaining = quota

        val reset = response.header(HEADER_RESET)?.trim()?.toLongOrNull()
        if (reset != null && reset > 0L) resetAtEpochSeconds = reset
    }

    /** Exponential backoff with jitter for 429/403/5xx and transport failures. */
    fun penalize(httpCode: Int, attempt: Int): Long {
        val base = BASE_BACKOFF_MS shl attempt.coerceIn(0, 4)
        val wait = (base + Random.nextLong(0L, JITTER_MS)).coerceAtMost(MAX_BACKOFF_MS)
        penaltyUntil = maxOf(penaltyUntil, SystemClock.elapsedRealtime() + wait)
        onEvent(ApiEvent.Throttled(wait, httpCode))
        return wait
    }

    private companion object {
        /** 0.5 req/s sustained, 2.2 s to stay clear of the 60/120 s window. */
        const val MIN_INTERVAL_MS = 2_200L
        const val LOW_QUOTA_FLOOR = 5
        const val MIN_PARK_MS = 2_000L
        const val MAX_PARK_MS = 90_000L
        const val BASE_BACKOFF_MS = 2_000L
        const val JITTER_MS = 750L
        const val MAX_BACKOFF_MS = 30_000L
        const val HEADER_REMAINING = "x-ratelimit-remaining"
        const val HEADER_RESET = "x-ratelimit-reset"
    }
}
