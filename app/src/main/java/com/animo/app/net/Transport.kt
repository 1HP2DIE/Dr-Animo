package com.animo.app.net

/**
 * One answer from a third-party page, independent of who fetched it.
 *
 * [channel] is part of the payload on purpose: when a host starts refusing the desktop fingerprint
 * the log line "which rung answered" is the only cheap way to tell an OkHttp success from a
 * browser-context success after the fact.
 */
data class TransportResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
    val channel: String,
)

/** One way of reaching a host: OkHttp today, an invisible WebView tomorrow. */
interface TransportChannel {

    val name: String

    suspend fun execute(url: String, headers: Map<String, String>, timeoutMs: Long): ChannelOutcome

    /**
     * The same rung with a request body.
     *
     * The default is [ChannelOutcome.Failed] rather than `Refused`: a channel that has no POST path
     * says nothing about the host's opinion of us, and `Refused` would arm that host's backoff and
     * make every later GET pay for a limitation that lives on our side.
     */
    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): ChannelOutcome = ChannelOutcome.Failed(UnsupportedOperationException("$name has no POST path"))
}

sealed interface ChannelOutcome {

    data class Ok(val response: TransportResponse) : ChannelOutcome

    /** This channel is refused (403/429/challenge page) — try the next one, not "dead". */
    data class Refused(val reason: String) : ChannelOutcome

    data class Failed(val error: Throwable) : ChannelOutcome
}

/**
 * The four markers every Cloudflare interstitial ships, in the form a body actually contains them.
 *
 * Scanned case-insensitively over the first [HEAD_CHARS] characters only: the markers live in the
 * `<head>`, and a long article that happens to quote "challenge-platform" halfway down the page is
 * a legitimate 200, not a block.
 */
internal object ChallengeMarkers {

    private const val HEAD_CHARS = 8 * 1024

    private val markers = listOf(
        "just a moment",
        "cf_chl",
        "attention required",
        "challenge-platform",
    )

    fun present(body: String): Boolean {
        if (body.isEmpty()) return false
        val head = if (body.length <= HEAD_CHARS) body else body.substring(0, HEAD_CHARS)
        return markers.any { head.contains(it, ignoreCase = true) }
    }
}
