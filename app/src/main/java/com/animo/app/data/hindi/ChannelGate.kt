package com.animo.app.data.hindi

import com.animo.app.net.ChallengeMarkers
import com.animo.app.net.TransportBlockedException
import com.animo.app.net.TransportRouter
import com.animo.app.player.ResolveException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.delay

/**
 * How a page answered, once the edge is accounted for.
 *
 * The Hindi hosts sit behind a CDN that challenges a request it does not like the look of, and the
 * scrapers used to fold that answer into "HTTP 403" — indistinguishable from an episode that was
 * pulled. [BLOCKED] is the channel, [DEAD] is the content: only [BLOCKED] is worth another attempt.
 */
internal enum class ChannelFault { OK, DEAD, BLOCKED, TRANSIENT, OTHER }

/**
 * One page fetch, whichever rung produced it.
 *
 * [channel] is carried up to the hop trace on purpose: "which rung answered" is the only cheap way
 * to tell an OkHttp success from a browser-context success after the fact (see [TransportResponse]),
 * and a support report needs it to say which rung a refusal landed on.
 */
internal class Fetched(
    val code: Int,
    val body: String,
    val channel: String,
    val fault: ChannelFault,
    val headers: Map<String, String> = emptyMap(),
    val error: Throwable? = null,
    val retryAfterMs: Long = 0L,
) {

    val isSuccessful: Boolean get() = code in 200..299

    /** Header lookup that is case-insensitive the way HTTP is, because the transport is a plain map. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Host-free one-liner for the hop trail: status, size, rung. */
    fun describe(): String = "http $code ${body.length}b channel=$channel"
}

/**
 * The one place that decides "blocked / gone / worth a retry" for every Hindi page fetch.
 *
 * Classification lives here rather than in each provider so a 403 cannot mean "dead episode" in one
 * file and "channel problem" in the next, and so the retry budget is the same everywhere: at most one
 * extra request, and only on a fault that can actually clear.
 */
internal object ChannelGate {

    /** Longest cooldown worth waiting inside a tap-to-play; beyond it an honest failure is cheaper. */
    private const val MAX_INLINE_RETRY_MS = 2_000L

    /** Breather before the single retry, so the two attempts are not one burst. */
    private const val RETRY_PAUSE_MS = 300L

    const val UNKNOWN_CHANNEL = "unknown"

    private val CHANNEL_IN_MESSAGE = Regex("channel=([A-Za-z0-9_.-]+)")

    /**
     * An HTTP answer, with [text] scanned for a challenge body only up to its `<head>`.
     *
     * 404/410 is checked first: a 410 body is a tombstone page, and one that happens to mention
     * "attention required" is still content. 407/408/425 and 5xx are transient.
     */
    fun verdict(
        code: Int,
        text: String = "",
        headers: Map<String, String> = emptyMap(),
    ): ChannelFault = when {
        code == 404 || code == 410 -> ChannelFault.DEAD
        code == 403 || code == 429 -> ChannelFault.BLOCKED
        code == 408 || code == 425 || code >= 500 -> ChannelFault.TRANSIENT
        headers.entries.firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }
            ?.value?.contains("challenge", ignoreCase = true) == true -> ChannelFault.BLOCKED
        text.isNotEmpty() && ChallengeMarkers.present(text) -> ChannelFault.BLOCKED
        code in 200..299 -> ChannelFault.OK
        else -> ChannelFault.OTHER
    }

    /**
     * The same verdict for a thrown failure.
     *
     * [TransportBlockedException] is both: `refused` when a rung was actually told no, plain
     * transient when every rung was a socket/DNS failure. A timeout is transient because it says
     * nothing about the content; a parse error (`OTHER`) must never be retried.
     */
    fun verdict(error: Throwable?): ChannelFault = when (error) {
        null -> ChannelFault.OTHER
        is TransportBlockedException ->
            if (error.refused) ChannelFault.BLOCKED else ChannelFault.TRANSIENT
        is ResolveException.Blocked -> ChannelFault.BLOCKED
        is ResolveException.Dead, is ResolveException.NotFound -> ChannelFault.DEAD
        is ResolveException -> ChannelFault.OTHER
        is SocketTimeoutException, is TimeoutException -> ChannelFault.TRANSIENT
        is IOException -> ChannelFault.TRANSIENT
        else -> ChannelFault.OTHER
    }

    fun isBlocked(error: Throwable): Boolean = verdict(error) == ChannelFault.BLOCKED

    /**
     * One page, with the single retry a [ChannelFault.BLOCKED] or [ChannelFault.TRANSIENT] answer is
     * allowed.
     *
     * A 404/410 is returned on the first answer: it is content, and a retry would only make a
     * support report slower. A cooldown longer than [MAX_INLINE_RETRY_MS] also returns immediately —
     * the user gets the message instead of a spinner, and the ladder is not hammered.
     */
    suspend fun fetch(
        router: TransportRouter,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        form: String? = null,
    ): Fetched {
        val first = once(router, url, headers, timeoutMs, form)
        if (first.fault != ChannelFault.BLOCKED && first.fault != ChannelFault.TRANSIENT) return first
        if (first.retryAfterMs > MAX_INLINE_RETRY_MS) return first
        delay(RETRY_PAUSE_MS)
        return once(router, url, headers, timeoutMs, form)
    }

    private suspend fun once(
        router: TransportRouter,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        form: String?,
    ): Fetched {
        val result = if (form == null) {
            router.get(url, headers, timeoutMs)
        } else {
            router.post(url, form, headers, timeoutMs)
        }
        val response = result.getOrNull() ?: return failure(result.exceptionOrNull())
        return Fetched(
            code = response.code,
            body = response.body,
            channel = response.channel,
            fault = verdict(response.code, response.body, response.headers),
            headers = response.headers,
        )
    }

    private fun failure(error: Throwable?): Fetched = Fetched(
        code = 0,
        body = "",
        channel = channelOf(error),
        fault = verdict(error),
        error = error,
        retryAfterMs = (error as? TransportBlockedException)?.retryAfterMs ?: 0L,
    )

    /** Which rung a failure landed on: the last one the ladder tried, or the one named in a message. */
    fun channelOf(error: Throwable?): String = when (error) {
        is TransportBlockedException ->
            error.attempts.lastOrNull()?.channel ?: UNKNOWN_CHANNEL
        null -> UNKNOWN_CHANNEL
        else -> CHANNEL_IN_MESSAGE.find(error.message.orEmpty())?.groupValues?.get(1)
            ?: UNKNOWN_CHANNEL
    }

    /**
     * The text the player's error sheet shows. No host: the user cannot act on a hostname, and the
     * hop trail already carries which rung and which status for the bug report.
     */
    fun blockedMessage(code: Int, channel: String, retryAfterMs: Long = 0L): String {
        val status = if (code > 0) "HTTP $code, " else ""
        val wait = when {
            retryAfterMs <= 0L -> "try again in a minute"
            retryAfterMs < 60_000L -> "try again in ${(retryAfterMs / 1_000L).coerceAtLeast(1L)}s"
            else -> "try again in a few minutes"
        }
        return "server is blocking playback (${status}channel=$channel) - $wait"
    }
}
