package com.animo.app.net.webview

/**
 * Recognises the interstitials an edge puts in front of a page when it wants a browser instead of
 * an HTTP client ("Just a moment", DDoS-Guard, Turnstile).
 *
 * Nothing is parsed as HTML and nothing is fetched: every marker below is a literal those pages
 * ship, and one hit is enough. Matching is deliberately generous — a false positive only means the
 * WebView is asked to warm up (and the replay decides for real), while a false negative would let
 * the ladder return a challenge page as if it were content.
 */
internal object ChallengeDetector {

    /** The cookie Cloudflare issues once its JS challenge passes. */
    val DEFAULT_CLEARANCE_COOKIES: Set<String> = setOf("cf_clearance")

    private val MARKERS: List<String> = listOf(
        "just a moment",
        "attention required",
        "checking your browser",
        "checking if the site connection is secure",
        "verifying you are human",
        "enable javascript and cookies to continue",
        "cf-browser-verification",
        "challenge-platform",
        "cf_chl_",
        "ddos-guard",
    )

    /**
     * First marker found in [title] or [body], or `null` when the page looks like real content.
     * The returned marker is logged and put in the `Refused` reason, so it stays a short literal.
     */
    fun marker(title: String?, body: String?): String? {
        val haystack = buildString {
            if (!title.isNullOrBlank()) append(title).append('\n')
            if (!body.isNullOrBlank()) append(body)
        }.lowercase()
        if (haystack.isEmpty()) return null
        return MARKERS.firstOrNull { it in haystack }
    }

    /**
     * Same test for a raw HTTP body. A JSON or plain-text payload is never scanned: an API is
     * allowed to contain the words "just a moment" in a title field, and losing that response
     * would be far worse than skipping a challenge check on it.
     */
    fun markerInDocument(document: String?): String? {
        if (document.isNullOrBlank()) return null
        if (!document.trimStart().startsWith("<")) return null
        return marker(null, document)
    }

    /** `cf_clearance=...` (or another configured name) is present in a `Cookie` header. */
    fun hasClearance(
        cookieHeader: String?,
        names: Set<String> = DEFAULT_CLEARANCE_COOKIES,
    ): Boolean = cookieNames(cookieHeader).any { name -> names.any { it.equals(name, ignoreCase = true) } }

    fun cookieNames(cookieHeader: String?): Set<String> {
        if (cookieHeader.isNullOrBlank()) return emptySet()
        return cookieHeader.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
