package com.animo.app.data.remote

/**
 * Signals emitted by [RequestGate] while a caller is being parked. The sync layer turns these into
 * `SyncState.THROTTLED` progress so the UI can explain a long-running refresh instead of looking
 * frozen.
 */
sealed interface ApiEvent {

    /** Parked because the previous response asked us to slow down (429/403/5xx). */
    data class Throttled(val waitMs: Long, val httpCode: Int = 0) : ApiEvent

    /** Parked because `x-ratelimit-remaining` dropped below the safety floor. */
    data class LowQuota(val remaining: Int, val waitMs: Long) : ApiEvent
}
