package com.animo.app.net

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class AttemptStatus { OK, REFUSED, FAILED }

/** One rung of the ladder, kept so a caller can see *which* channel refused instead of just "blocked". */
data class ChannelAttempt(
    val channel: String,
    val status: AttemptStatus,
    val detail: String,
)

/**
 * Every channel refused or failed, so the caller's next move is "show a different message", not
 * "retry the same URL".
 *
 * A single type covers both cases on purpose: callers of the router already branch on failure, and
 * adding a second exception for "the ladder was all network errors" would leave them catching one
 * branch and crashing on the other. [refused] tells the two apart when it matters, and [cause] keeps
 * the transport error for the log.
 */
class TransportBlockedException(
    val host: String,
    val attempts: List<ChannelAttempt>,
    /** When the host is free again (cooldown or pacing remainder), so a caller can back off. */
    val retryAfterMs: Long,
    cause: Throwable? = null,
) : IOException(blockedMessage(host, attempts, retryAfterMs), cause) {

    val refused: Boolean get() = attempts.any { it.status == AttemptStatus.REFUSED }
}

private fun blockedMessage(host: String, attempts: List<ChannelAttempt>, retryAfterMs: Long): String {
    if (attempts.isEmpty()) return "no transport channel configured for $host"
    val rungs = attempts.joinToString(", ") { "${it.channel}=${it.status.name.lowercase()}(${it.detail.take(120)})" }
    return "all channels exhausted for $host: $rungs; retry in ${retryAfterMs}ms"
}

/**
 * Walks the channel ladder until one rung answers.
 *
 * Exactly one pass per call. Replaying the ladder internally is what turns one blocked provider into
 * a burst of identical requests to the same host, which is the block signal itself — so a caller who
 * wants another attempt gets it by calling again, after [HostBudget]'s cooldown.
 */
class TransportRouter(
    private val channels: List<TransportChannel>,
    private val budget: HostBudget = HostBudget(),
    private val log: (String) -> Unit = {},
) {

    /** Drops every per-host penalty; the network underneath changed (see [HostBudget.clear]). */
    fun resetBudget() = budget.clear()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000L,
    ): Result<TransportResponse> = walk("GET", url, null, headers, timeoutMs)

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000L,
    ): Result<TransportResponse> = walk("POST", url, body, headers, timeoutMs)

    private suspend fun walk(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<TransportResponse> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("transport url is blank"))
        val host = hostOf(url)
        if (channels.isEmpty()) {
            return Result.failure(TransportBlockedException(host, emptyList(), 0L))
        }

        val attempts = ArrayList<ChannelAttempt>(channels.size)
        var firstFailure: Throwable? = null

        channels.forEachIndexed { index, channel ->
            val paced = budget.await(host, honorBackoff = index == 0)
            if (paced > 0) log("transport $method host=$host paced ${paced}ms before ${channel.name}")

            val outcome = runRung(method, url, body, headers, timeoutMs, channel)
            when (outcome) {
                is ChannelOutcome.Ok -> {
                    val response = outcome.response
                    val refused = refusalOf(response)
                    if (refused == null) {
                        budget.onServed(host)
                        attempts += ChannelAttempt(channel.name, AttemptStatus.OK, "http ${response.code}")
                        log("transport $method host=$host channel=${channel.name} ok http ${response.code}")
                        return Result.success(response)
                    }
                    budget.onRefused(host)
                    attempts += ChannelAttempt(channel.name, AttemptStatus.REFUSED, refused)
                    log("transport $method host=$host channel=${channel.name} refused ($refused http ${response.code})")
                }

                is ChannelOutcome.Refused -> {
                    budget.onRefused(host)
                    attempts += ChannelAttempt(channel.name, AttemptStatus.REFUSED, outcome.reason)
                    log("transport $method host=$host channel=${channel.name} refused (${outcome.reason})")
                }

                is ChannelOutcome.Failed -> {
                    // No cooldown: a DNS or socket failure is not the host objecting to us, and
                    // charging it would make the first real block arrive pre-armed.
                    if (firstFailure == null) firstFailure = outcome.error
                    val reason = "${outcome.error.javaClass.simpleName}: ${outcome.error.message.orEmpty()}"
                    attempts += ChannelAttempt(channel.name, AttemptStatus.FAILED, reason)
                    log("transport $method host=$host channel=${channel.name} failed ($reason)")
                }
            }
        }

        val retryAfterMs = budget.waitForMs(host)
        log("transport $method host=$host blocked after ${attempts.size} channel(s)")
        return Result.failure(
            TransportBlockedException(host, attempts, retryAfterMs, firstFailure),
        )
    }

    /**
     * A channel is a black box; one that forgets to classify still must not hand the caller a
     * Cloudflare 403 as if it were content. 404/410 stay `Ok` because they are answers.
     */
    private fun refusalOf(response: TransportResponse): String? = when {
        response.code == 403 || response.code == 429 -> "http ${response.code}"
        ChallengeMarkers.present(response.body) -> "challenge page with http ${response.code}"
        else -> null
    }

    /**
     * Runs one rung with a watchdog [GRACE_MS] beyond the channel's own timeout.
     *
     * A channel that ignores `timeoutMs` (or a future WebView-backed one that can stall on the
     * renderer) must not be able to hold the caller forever; when the watchdog fires the call is
     * cancelled and the next rung gets its turn.
     */
    private suspend fun runRung(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Long,
        channel: TransportChannel,
    ): ChannelOutcome {
        val bounded = timeoutMs.coerceAtLeast(1_000L) + GRACE_MS
        return try {
            withTimeoutOrNull(bounded) {
                if (body == null) {
                    channel.execute(url, headers, timeoutMs)
                } else {
                    channel.post(url, body, headers, timeoutMs)
                }
            } ?: ChannelOutcome.Failed(IOException("channel watchdog fired after ${bounded}ms"))
        } catch (e: TimeoutCancellationException) {
            ChannelOutcome.Failed(IOException("channel timed out after ${timeoutMs}ms", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChannelOutcome.Failed(e)
        }
    }

    private companion object {
        /** Room for a bounded body read on top of whatever the channel already enforces. */
        const val GRACE_MS = 2_000L
    }
}

/** Pacing key: scheme and port do not change who is counting requests, the host does. */
internal fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }
        .getOrNull()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: url
