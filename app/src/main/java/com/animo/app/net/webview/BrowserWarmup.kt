package com.animo.app.net.webview

/**
 * The one WebView-shaped seam in this package.
 *
 * The real implementation ([AndroidBrowserWarmup]) needs a main thread, a live renderer and an
 * installed System WebView; none of that exists on the JVM. Everything above this interface — the
 * decision flow in [WebViewSession] — is plain Kotlin so it can be unit-tested with a fake.
 */
internal interface BrowserWarmup {

    /**
     * Loads [url] once in a throw-away WebView and waits until the edge is satisfied or
     * [timeoutMs] expires. Never throws: every failure is a [WarmupResult].
     */
    suspend fun warm(url: String, timeoutMs: Long): WarmupResult
}

internal sealed interface WarmupResult {

    /**
     * The page settled without challenge markers, or a clearance cookie appeared.
     * [cookies] is the jar's header for the page at that moment, when one could be read.
     */
    data class Solved(val cookies: String?) : WarmupResult

    /**
     * The timeout elapsed and the last finished page still carried challenge markers.
     * [marker] is the literal that matched, for logs and for the `Refused` reason.
     */
    data class StillChallenged(val marker: String?) : WarmupResult

    /**
     * [fatal] means "this host cannot be warmed in this process again" (WebView could not be
     * constructed, renderer is unusable). A page-level error is not fatal: the next call retries.
     */
    data class Failed(
        val message: String,
        val fatal: Boolean = false,
        val cause: Throwable? = null,
    ) : WarmupResult

    /** Nothing finished in time. Distinct from [StillChallenged]: no page was ever inspected. */
    data object TimedOut : WarmupResult

    /** This ROM has no usable System WebView (`WebView` construction threw). */
    data object Unavailable : WarmupResult
}
