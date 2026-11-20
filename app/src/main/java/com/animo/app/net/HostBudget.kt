package com.animo.app.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-host pacing: min interval + exponential backoff, in-memory only.
 *
 * Deliberately not persisted. A refusal is a statement about *this* IP right now; replaying a
 * stored 30 s penalty after a restart would delay the first useful request for no reason, and the
 * next refusal re-arms it immediately anyway.
 *
 * [now] and [sleeper] are injectable so the pacing can be asserted without wall-clock sleeps: the
 * budget itself has no timing source of its own.
 */
class HostBudget(
    private val minIntervalMs: Long = 800L,
    private val maxBackoffMs: Long = 30_000L,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private data class HostState(
        val lastStartedAt: Long = NEVER,
        val backoffUntil: Long = 0L,
        val strikes: Int = 0,
    )

    /**
     * One mutex for every host, not one per host.
     *
     * The whole point is that a single IP with a burst of requests to three hosts still leaves the
     * wire one request at a time; per-host locks would let two hosts fire simultaneously and hand
     * the block signal back to us. Contention is 2-4 coroutines, so the cost is noise.
     */
    private val gate = Mutex()

    private val lock = Any()
    private val states = HashMap<String, HostState>()

    /**
     * Suspends until this caller may fire at [host], then stamps the start.
     *
     * [honorBackoff] is false for the rungs after the first of a single call: a refusal just armed
     * the cooldown, and waiting it out inside the same call would spend the caller's timeout on the
     * channel that already said no.
     */
    suspend fun await(host: String, honorBackoff: Boolean = true): Long = gate.withLock {
        val startedAt = now()
        val wait = waitForMs(host, honorBackoff, startedAt)
        if (wait > 0) sleeper(wait)
        synchronized(lock) {
            val state = states[host] ?: HostState()
            states[host] = state.copy(lastStartedAt = now())
        }
        wait.coerceAtLeast(0L)
    }

    /** Milliseconds this host would still have to wait at [atMs]; 0 when it is free. */
    fun waitForMs(host: String, honorBackoff: Boolean = true, atMs: Long = now()): Long =
        synchronized(lock) {
            val state = states[host] ?: return 0L
            val earliest = maxOf(
                if (state.lastStartedAt == NEVER) 0L else state.lastStartedAt + minIntervalMs,
                if (honorBackoff) state.backoffUntil else 0L,
            )
            (earliest - atMs).coerceAtLeast(0L)
        }

    /** Epoch-free clock value from [now] at which [host] stops cooling down; 0 when it is not. */
    fun backoffUntilMs(host: String): Long = synchronized(lock) { states[host]?.backoffUntil ?: 0L }

    /** Consecutive refusals currently charged to [host]. */
    fun strikes(host: String): Int = synchronized(lock) { states[host]?.strikes ?: 0 }

    /**
     * Arms the next cooldown and returns the delay in milliseconds.
     *
     * Doubling from [minIntervalMs] keeps the first penalty short enough that a single false
     * positive does not cost a user-visible stall, while the [maxBackoffMs] cap keeps a hard block
     * from parking a host for minutes.
     */
    fun onRefused(host: String): Long = synchronized(lock) {
        val state = states[host] ?: HostState()
        val strikes = state.strikes + 1
        val wait = (minIntervalMs shl (strikes - 1).coerceIn(0, 15)).coerceAtMost(maxBackoffMs)
        val until = now() + wait
        states[host] = state.copy(
            strikes = strikes,
            backoffUntil = maxOf(state.backoffUntil, until),
        )
        wait
    }

    /**
     * A 200 proves the earlier refusal was the host being picky, not the host being shut: clearing
     * both the strikes and the cooldown is what lets the next call go out immediately instead of
     * inheriting a 30 s penalty that no longer reflects anything.
     */
    fun onServed(host: String) {
        synchronized(lock) {
            val state = states[host] ?: return
            states[host] = state.copy(backoffUntil = 0L, strikes = 0)
        }
    }

    /**
     * Forgets every cooldown and every strike.
     *
     * A refusal is a statement about the IP that made the request, so the instant the phone is on a
     * different network that statement is stale: keeping a 30 s penalty across a Wi-Fi→mobile hand
     * over would punish the caller for something that is no longer true.
     */
    fun clear() {
        synchronized(lock) { states.clear() }
    }

    private companion object {
        /** Far enough back that "never fired" cannot be confused with `0` (a real clock value). */
        const val NEVER = Long.MIN_VALUE / 2
    }
}
