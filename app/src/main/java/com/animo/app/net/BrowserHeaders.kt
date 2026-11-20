package com.animo.app.net

import com.animo.app.data.remote.HttpClientFactory
import java.net.URI

/**
 * A navigational request that looks like a desktop Chrome tab, not like an Android HTTP client.
 *
 * Every value here was chosen because a provider fingerprints on it: the `sec-ch-ua*` trio and the
 * `sec-fetch-*` trio are what a Cloudflare "browser integrity" rule compares against the UA string,
 * and a UA that says Chrome 124 while the fetch metadata is missing is more suspicious than a plain
 * OkHttp client. Chrome also sends the `sec-*` headers in lowercase and the rest in title case; the
 * map keeps that casing so a byte-level comparison against a real browser matches.
 *
 * No hostname appears in this file — the caller supplies the URL and the referer.
 */
object BrowserHeaders {

    private const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
            "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"

    private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    /** Matches Chrome 124 — the same major version [HttpClientFactory.UA_DESKTOP] claims. */
    private const val SEC_CH_UA = "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\""

    private const val PLATFORM = "\"Windows\""

    private const val HEADER_COUNT = 11

    fun forUrl(url: String, referer: String? = null): Map<String, String> {
        val trimmedReferer = referer?.trim()?.takeIf { it.isNotEmpty() }
        val headers = LinkedHashMap<String, String>(HEADER_COUNT)

        headers["User-Agent"] = HttpClientFactory.UA_DESKTOP
        headers["Accept"] = ACCEPT
        headers["Accept-Language"] = ACCEPT_LANGUAGE
        headers["sec-ch-ua"] = SEC_CH_UA
        headers["sec-ch-ua-mobile"] = "?0"
        headers["sec-ch-ua-platform"] = PLATFORM
        headers["sec-fetch-site"] = fetchSite(url, trimmedReferer)
        headers["sec-fetch-mode"] = "navigate"
        headers["sec-fetch-dest"] = "document"
        headers["upgrade-insecure-requests"] = "1"

        if (trimmedReferer != null) {
            // Chrome only sends these two on a real navigation; a scrape from a bare client that
            // sends them on a top-level load is itself a tell.
            headers["Referer"] = trimmedReferer
            originOf(trimmedReferer)?.let { headers["Origin"] = it }
        }

        return headers
    }

    /**
     * Kotlin cannot call a function named `for` without backticks, so this is the name callers use;
     * the backticked alias below exists only to keep the documented spelling compilable.
     */
    fun `for`(url: String, referer: String? = null): Map<String, String> = forUrl(url, referer)

    /**
     * `none` for a tab typed by a human, `same-origin`/`same-site` for a navigation that came from
     * somewhere. Without a public-suffix list, "same site" is the last two labels, which is right
     * for the shapes providers use and never worth a 100 KB dataset in the APK.
     */
    private fun fetchSite(url: String, referer: String?): String {
        if (referer == null) return "none"
        val target = hostOf(url)
        val source = hostOf(referer)
        return when {
            target.isEmpty() || source.isEmpty() -> "cross-site"
            target == source -> "same-origin"
            siteOf(target) == siteOf(source) -> "same-site"
            else -> "cross-site"
        }
    }

    private fun siteOf(host: String): String {
        val labels = host.trimEnd('.').split('.')
        return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
    }

    private fun originOf(referer: String): String? = runCatching {
        val uri = URI(referer)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        if (uri.port > 0 && uri.port != defaultPort(scheme)) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }.getOrNull()

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
