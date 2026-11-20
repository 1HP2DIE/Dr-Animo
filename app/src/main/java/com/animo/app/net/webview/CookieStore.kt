package com.animo.app.net.webview

/**
 * What the WebView leaves behind: the process-wide cookie jar (`android.webkit.CookieManager`).
 *
 * The jar is the whole point of the warm-up — the challenge writes `cf_clearance` there, and the
 * replay only has to read it back. Keeping it behind an interface makes the replay decision
 * testable without Android.
 */
internal interface CookieStore {

    /** Cookie header for [url], or `null` when the jar holds nothing for it. */
    fun cookieHeader(url: String): String?

    /** Drops every cookie the jar returns for [host]. Used on logout and on network change. */
    fun clearHost(host: String)

    /** Drops the whole jar. */
    fun clearAll()

    /** Persists the jar to disk when the platform supports it. A no-op on a fake. */
    fun flush() = Unit
}
