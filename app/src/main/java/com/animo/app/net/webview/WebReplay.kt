package com.animo.app.net.webview

/**
 * The second half of the warm-up: after the challenge is solved, the *same* request is issued
 * again over OkHttp, carrying the jar's cookies.
 *
 * A raw HTTP replay is possible only because the session (not the WebView) holds the payload the
 * app actually needs; the WebView is used once, for its side effect on the jar, and is destroyed
 * before this runs.
 */
internal fun interface WebReplay {

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String?,
        timeoutMs: Long,
    ): ReplayOutcome
}

internal sealed interface ReplayOutcome {

    /** A response that is not a challenge page. */
    data class Response(
        val code: Int,
        val body: String,
        val headers: Map<String, String>,
    ) : ReplayOutcome

    /** The edge answered with (or redirected to) an interstitial despite the cookies. */
    data class Challenged(val code: Int, val marker: String) : ReplayOutcome

    /** Transport-level failure: timeout, DNS, TLS, a non-challenge 4xx/5xx. */
    data class Error(val error: Throwable) : ReplayOutcome
}
