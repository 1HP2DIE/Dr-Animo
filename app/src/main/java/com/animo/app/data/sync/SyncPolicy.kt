package com.animo.app.data.sync

/**
 * When a title is worth spending a network request on.
 *
 * The previous policy was "everything, every night": a 95-page catalog walk (3.2 min) plus a detail
 * pass, every single day, for a catalogue where only **258** titles are still airing (measured).
 * That is both slow and the fastest way to get rate-limited.
 *
 * Rules:
 *
 *  - **Airing / upcoming** (`currently_airing`, `not_yet_aired`): one episode refresh per
 *    [ONGOING_INTERVAL_MS]. Their episode lists genuinely change; nobody else's do.
 *  - **Finished and never detailed**: **not** fetched in the background at all. 8,967 of the 9,069
 *    finished rows are in this state, and fetching them blind is 5.5 hours of requests for lists the
 *    user may never open. They are fetched when the user actually opens the detail screen
 *    ([shouldFetchOnOpen]).
 *  - **Finished with a known gap** (`episodeCount < episodesDeclared` after a real pass): retried
 *    weekly until the gap closes — that is the "missing episodes" queue.
 *  - **Complete** (finished, declared > 0, stored >= declared): **never** again. Served from Room
 *    until the catalog delta moves the row or the user forces a refresh.
 *  - **Blank status**: monthly. The API answers `""` for some rows and re-asking nightly is waste.
 *
 * Pure Kotlin, no Android imports, so every rule is unit-testable.
 */
object SyncPolicy {

    /** Airing shows change weekly (one episode), so a 7-day window can never miss a release. */
    const val ONGOING_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** Gaps get the same weekly retry: a provider that missed episodes may have them next week. */
    const val GAP_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** Rows we know nothing more about (blank status, declared 0): recheck occasionally, not nightly. */
    const val UNKNOWN_RECHECK_MS = 30L * 24 * 60 * 60 * 1000

    /** Rows that entered the catalogue in this window are new; their episodes are unknown. */
    const val NEW_ROW_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    const val STATUS_AIRING = "currently_airing"
    const val STATUS_FINISHED = "finished_airing"
    const val STATUS_UPCOMING = "not_yet_aired"

    private fun isAiring(statusNorm: String) =
        statusNorm == STATUS_AIRING || statusNorm == STATUS_UPCOMING

    /**
     * A finished title whose stored episode count reached what the catalog declared. Such a row has
     * nothing left to learn: the show ended and every episode is already listed.
     */
    fun isComplete(
        statusNorm: String,
        episodesDeclared: Int,
        episodeCount: Int,
        detailFetchedAt: Long?,
    ): Boolean = statusNorm == STATUS_FINISHED &&
        detailFetchedAt != null &&
        episodesDeclared > 0 &&
        episodeCount >= episodesDeclared

    /**
     * Absolute next time this row may be fetched **in the background**, or `null` when the background
     * work should never touch it again (complete, or a finished title nobody has opened yet).
     */
    fun dueAt(
        statusNorm: String,
        episodesDeclared: Int,
        episodeCount: Int,
        detailFetchedAt: Long?,
    ): Long? {
        if (isComplete(statusNorm, episodesDeclared, episodeCount, detailFetchedAt)) return null

        // Never detailed: only worth a request if the show is actually running. A finished title
        // waits for the user to open it — this is the single biggest saving in the whole policy.
        if (detailFetchedAt == null) return if (isAiring(statusNorm)) 0L else null

        // A declared-versus-stored gap means episodes are visibly missing right now.
        if (episodesDeclared > episodeCount) return detailFetchedAt + GAP_INTERVAL_MS

        return detailFetchedAt + if (isAiring(statusNorm)) ONGOING_INTERVAL_MS else UNKNOWN_RECHECK_MS
    }

    fun isDue(
        statusNorm: String,
        episodesDeclared: Int,
        episodeCount: Int,
        detailFetchedAt: Long?,
        now: Long,
    ): Boolean = dueAt(statusNorm, episodesDeclared, episodeCount, detailFetchedAt)
        ?.let { it <= now } ?: false

    /**
     * The on-demand path (a detail screen opening). It is allowed to do what the background is not:
     * fetch a finished title that has never been detailed, because the user is looking at it.
     */
    fun shouldFetchOnOpen(
        statusNorm: String,
        episodesDeclared: Int,
        episodeCount: Int,
        detailFetchedAt: Long?,
        now: Long,
    ): Boolean = when {
        detailFetchedAt == null -> true
        isComplete(statusNorm, episodesDeclared, episodeCount, detailFetchedAt) -> false
        else -> isDue(statusNorm, episodesDeclared, episodeCount, detailFetchedAt, now)
    }

    /**
     * Lower runs sooner.
     *  0. a brand-new row (came in with the catalog delta) — its detail screen would otherwise be empty
     *  1. a declared-vs-stored gap — the user can already see the missing episodes
     *  2. airing / upcoming — due weekly
     *  3. everything else that is merely stale
     */
    fun priority(
        isNew: Boolean,
        episodesDeclared: Int,
        episodeCount: Int,
        statusNorm: String,
    ): Int = when {
        isNew -> 0
        episodesDeclared > episodeCount -> 1
        isAiring(statusNorm) -> 2
        else -> 3
    }
}
