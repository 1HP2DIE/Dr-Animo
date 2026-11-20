package com.animo.app.data.hindi

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault

import com.animo.app.AppLog
import com.animo.app.data.remote.Normalize
import com.animo.app.di.ServiceLocator
import com.animo.app.net.TransportRouter
import com.animo.app.player.ResolveException
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * toono.app — the P0 Hindi-dub source (research/HINDI_SOURCES.md §1).
 *
 * Network + parsing only: no Room, no DI, no player. [catalog] enumerates, [title] details a page,
 * [embedId] resolves the two hops that end at `argon.razorshell.space/embed/<videoId>`. The juicy.codes
 * playlist decode behind that embed belongs to the decoder, not here.
 *
 * Everything is scraped, so every regex below encodes a structure that was measured live on
 * 2026-11-17; the KDoc notes what was verified and what a caller may not assume.
 */
class ToonoProvider(
    private val http: OkHttpClient,
    /**
     * The channel ladder for every page fetch. `null` means "raw OkHttp only", which is what unit
     * tests and an un-installed container get; production resolves [ServiceLocator.transport] on
     * first use.
     */
    private val transport: TransportRouter? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolved lazily so constructing a provider never touches the container: the tests build one
     * with a fake OkHttp client and no Android runtime, and a `NullPointerException` there would be
     * indistinguishable from a parser bug.
     */
    private val router: TransportRouter? by lazy {
        transport ?: if (ServiceLocator.isInstalled()) {
            runCatching { ServiceLocator.transport }.getOrNull()
        } else {
            null
        }
    }

    // ------------------------------------------------------------------ catalog

    /**
     * One page of the catalogue via the theme's "Load more" AJAX.
     *
     * The measured endpoint is `POST /wp-admin/admin-ajax.php` with
     * `action=action_search&vars=<json>`, and it **rejects anything without `_wpsearch`** — a stale
     * nonce answers HTTP 200 with `{"status":400}` (not an HTTP error), so [ajax] inspects the body.
     * No `X-WP-Nonce` header is needed; the token travels inside `vars`.
     *
     * [hindiOnly] keeps `taxonomy=category&term=197` (the `hindi` category measured on
     * `/category/hindi/`), which is the honest slice for this source: 126 pages of 10 = **1,260
     * distinct titles**, page 126 is the last (`next=false`), page 127+ returns `html:""`. Without it
     * the same enumeration returns the whole site, but then `isHindi` is *not* known — cards carry no
     * language tags, so unfiltered rows must be re-checked through [title].
     *
     * Returning an empty list means "past the last page"; it is never an error.
     */
    suspend fun catalog(page: Int, hindiOnly: Boolean = true): List<DubTitleA> {
        val safePage = page.coerceAtLeast(1)
        val body = ajax(
            page = safePage,
            taxonomy = if (hindiOnly) TAXONOMY_CATEGORY else TAXONOMY_NONE,
            term = if (hindiOnly) HINDI_CATEGORY_ID else TERM_NONE,
            type = TYPE_MIXED,
        )

        val html = try {
            json.parseToJsonElement(body).jsonObject["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (error: Exception) {
            throw ToonoException("catalog page $safePage: unparseable AJAX body", error)
        }

        val titles = parseCards(html, hindiOnly)
        AppLog.d("source A catalogue page $safePage: ${titles.size} titles")
        return titles
    }

    // ------------------------------------------------------------------ detail

    /**
     * Parses a title page — `/series/<slug>/` or `/movies/<slug>/`.
     *
     * [slug] accepts a bare slug (`1-happy-family-usa`), a `series/…` or `movies/…` path, or the full
     * URL; a bare slug is probed against `/series/` first and `/movies/` second, because the two
     * routes are mutually exclusive (measured: `/series/tarzan-jane/` 404s, `/movies/tarzan-jane/`
     * 200s).
     *
     * The Hindi flag comes from the page's own `span.categories` anchors — the same tags the AJAX
     * cards omit. Multi-language titles are real: `doraemon-season-21` carries
     * `Hindi · Tamil · Telugu`.
     */
    suspend fun title(slug: String): DubTitleA {
        val path = canonicalPath(slug)
        val candidates = if (path != null) {
            listOf(path)
        } else {
            val bare = slug.trim().trim('/').substringAfterLast('/')
            if (bare.isEmpty()) throw ToonoException("title: blank slug")
            listOf("$SERIES/$bare", "$MOVIES/$bare")
        }

        var lastCode = 0
        for (candidate in candidates) {
            val page = fetch("$BASE/$candidate/", referer = "$BASE/")
            lastCode = page.code
            if (page.isOk()) return parseTitlePage(candidate, page.body)
        }
        throw ToonoException("title '$slug': HTTP $lastCode")
    }

    // ------------------------------------------------------------------ embed

    /**
     * Resolves an episode **or movie** page to the argon embed id.
     *
     * Measured chain (2026-11-17, three targets incl. `1-happy-family-usa-1x1`):
     * 1. the page's `#main-player` iframe already holds
     *    `https://toono.app/?trembed=1&trid=39519&trtype=2` (`trtype=1` for movie pages), so the URL
     *    is read rather than reconstructed — an episode page also carries eight sibling
     *    `data-episode-id` attributes and the first one is not necessarily the episode being viewed;
     * 2. that `trembed` URL answers a ~620 B body with one `<iframe src="https://<host>/embed/<id>">`;
     * 3. the id is returned. `argon.razorshell.space` served 3/3 sampled targets.
     *
     * Only when the iframe is absent does it fall back to `data-episode-id` + a built
     * `trembed=1&trtype=2` URL. Throws [ToonoException] rather than returning blank: an empty id is
     * always a parsing regression, never a valid "no stream".
     */
    suspend fun embedId(episodeUrl: String): String {
        val pageUrl = absoluteUrl(episodeUrl)
        val page = fetch(pageUrl, referer = "$BASE/")
        if (!page.isOk()) throw ToonoException("embed: $pageUrl answered HTTP ${page.code}")

        val trembed = parsePlayerFrame(page.body)
            ?: parseFallbackTrembed(page.body)
            ?: throw ToonoException("embed: no player frame on $pageUrl")

        val embed = fetch(trembed, referer = pageUrl, accept = "*/*")
        if (!embed.isOk()) throw ToonoException("embed: trembed answered HTTP ${embed.code}")

        val videoId = match(EMBED_ID, embed.body)
            ?: throw ToonoException("embed: no /embed/<id> in trembed response for $pageUrl")

        AppLog.d("source A embed -> $videoId")
        return videoId
    }

    // ------------------------------------------------------------------ AJAX transport

    /**
     * Fetches the theme nonce from a normal page. Measured: one and the same value
     * (`9e1a604b08`) appeared on the home page, `/series/`, a category page, a title page and an
     * episode page, and it survived 126 sequential AJAX calls plus a later page load — it is
     * per-session, not per-request. It is still cached only until the server rejects it, because a
     * WordPress nonce does expire and a hard-coded TTL would either waste requests or stall.
     */
    private suspend fun nonce(refresh: Boolean = false): String {
        if (!refresh) cachedNonce?.let { return it }

        return nonceLock.withLock {
            if (!refresh) cachedNonce?.let { return@withLock it }

            val page = fetch("$BASE/", referer = "$BASE/")
            if (!page.isOk()) throw ToonoException("nonce: home page answered HTTP ${page.code}")

            val token = parseNonce(page.body)
                ?: throw ToonoException("nonce: 'var toronites' block not found on home page")

            cachedNonce = token
            token
        }
    }

    private suspend fun ajax(page: Int, taxonomy: String, term: String, type: String): String {
        var lastError: Throwable? = null

        for (attempt in 0 until AJAX_ATTEMPTS) {
            val token = nonce(refresh = attempt > 0)
            val vars = buildVars(page, taxonomy, term, type, token)
            val form = "action=$AJAX_ACTION&vars=" + URLEncoder.encode(vars.toString(), "UTF-8")

            val response = postForm(AJAX_ENDPOINT, referer = "$BASE/series/", form = form)
            if (!response.isOk()) {
                // A 403 here is the edge refusing the channel, not the page being gone: the sync must
                // stop with a "blocked" message instead of retrying the same POST three times.
                if (ChannelGate.verdict(response.code, response.body) == ChannelFault.BLOCKED) {
                    throw ResolveException.Blocked(
                        ChannelGate.blockedMessage(response.code, ChannelGate.UNKNOWN_CHANNEL),
                    )
                }
                lastError = ToonoException("ajax page $page: HTTP ${response.code}")
                continue
            }
            // A rejected nonce is HTTP 200 + {"status":400}; anything else is a real payload.
            if (response.body.contains(STATUS_400_MARKER)) {
                lastError = ToonoException("ajax page $page: nonce rejected")
                cachedNonce = null
                continue
            }
            return response.body
        }

        throw lastError ?: ToonoException("ajax page $page: no response")
    }

    private fun buildVars(
        page: Int,
        taxonomy: String,
        term: String,
        type: String,
        token: String,
    ): JsonObject = buildJsonObject {
        put("_wpsearch", token)
        put("taxonomy", taxonomy)
        put("search", SEARCH_NONE)
        put("term", term)
        put("type", type)
        put("genres", buildJsonArray { })
        put("years", buildJsonArray { })
        put("sort", 1)
        put("page", page)
    }

    // ------------------------------------------------------------------ parsing: listing

    private fun parseCards(html: String, hindiOnly: Boolean): List<DubTitleA> {
        val out = ArrayList<DubTitleA>(PAGE_SIZE)

        var start = html.indexOf(CARD_OPEN)
        while (start >= 0) {
            val close = html.indexOf(CARD_CLOSE, start)
            val end = if (close < 0) html.length else close
            parseCard(html.substring(start, end), hindiOnly)?.let(out::add)
            if (close < 0) break
            start = html.indexOf(CARD_OPEN, end)
        }

        return out
    }

    private fun parseCard(block: String, hindiOnly: Boolean): DubTitleA? {
        val title = match(CARD_TITLE, block)?.let(Normalize::cleanText) ?: return null
        val link = match(CARD_LINK, block) ?: return null
        val slug = match(CARD_SLUG, block) ?: link.substringAfterLast('/')

        val genres = match(CARD_GENRES, block)
            ?.let { GENRE_LABEL.findAll(it).map { m -> Normalize.cleanText(m.groupValues[1]) }.toList() }
            .orEmpty()

        return DubTitleA(
            id = link,
            slug = slug,
            title = title,
            poster = match(CARD_POSTER, block),
            year = match(CARD_YEAR, block)?.toIntOrNull(),
            isHindi = hindiOnly,
            languages = if (hindiOnly) listOf(LANGUAGE_HINDI) else emptyList(),
            genres = genres,
            episodeUrls = emptyList(),
        )
    }

    // ------------------------------------------------------------------ parsing: title page

    private fun parseTitlePage(id: String, html: String): DubTitleA {
        val slug = id.substringAfterLast('/')

        val headerStart = html.indexOf("<h1 class=\"entry-title\"")
        val header = if (headerStart >= 0) {
            html.substring(headerStart, minOf(headerStart + HEADER_WINDOW, html.length))
        } else {
            ""
        }

        val rawTitle = match(OG_TITLE, html)?.let(Normalize::cleanText)
            ?: match(H1_TITLE, header)?.let(Normalize::cleanText)?.substringBefore(" — ")
            ?: slug

        val categories = match(CATEGORIES, header).orEmpty()
        val tags = CATEGORY_TAG.findAll(categories).map { m ->
            Normalize.cleanText(m.groupValues[1]) to m.groupValues[2]
        }.toList()

        val languages = tags.filter { (_, categorySlug) -> categorySlug in LANGUAGE_CATEGORY_SLUGS }
            .map { (label, _) -> label }
        val genres = tags.filter { (_, categorySlug) -> categorySlug !in LANGUAGE_CATEGORY_SLUGS }
            .map { (label, _) -> label }

        val articleStart = html.indexOf("<article class=\"post single\"")
        val article = if (articleStart >= 0) {
            html.substring(articleStart, minOf(articleStart + ARTICLE_WINDOW, html.length))
        } else {
            header
        }

        val synopsis = match(SYNOPSIS, article)
            ?.let { Normalize.cleanText(TAG.replace(it, " ")) }
            .orEmpty()

        return DubTitleA(
            id = id,
            slug = slug,
            title = rawTitle,
            poster = match(POSTER, article),
            year = match(YEAR, header)?.toIntOrNull(),
            isHindi = languages.any { it.equals(LANGUAGE_HINDI, ignoreCase = true) },
            languages = languages,
            genres = genres,
            episodeUrls = parseEpisodeUrls(html),
            synopsis = synopsis,
            rating = match(RATING, header)?.toDoubleOrNull(),
        )
    }

    /**
     * Episode list. Each row is `<li data-episode-id="39519">…<a href="…/episode/<slug>/">`; the `li`
     * has no nested `li`, so the next `</li>` bounds it. Movie pages have no rows at all (the film
     * plays on the title page itself), which is why an empty list is meaningful rather than broken.
     */
    private fun parseEpisodeUrls(html: String): List<String> {
        val out = ArrayList<String>()
        var row = EPISODE_ROW.find(html)

        while (row != null) {
            val close = html.indexOf("</li>", row.range.last)
            val end = if (close < 0) html.length else close
            val href = match(EPISODE_LINK, html.substring(row.range.first, end))
            if (href != null && href !in out) out.add(href)
            if (close < 0) break
            row = EPISODE_ROW.find(html, end)
        }

        return out
    }

    private fun parsePlayerFrame(html: String): String? =
        match(PLAYER_FRAME, html)?.replace("&amp;", "&")
            ?: match(ANY_TREMBED_FRAME, html)?.replace("&amp;", "&")

    private fun parseFallbackTrembed(html: String): String? {
        val id = match(FALLBACK_EPISODE_ID, html) ?: return null
        return "$BASE/?trembed=1&trid=$id&trtype=2"
    }

    private fun parseNonce(html: String): String? {
        val blockStart = html.indexOf("var toronites")
        if (blockStart < 0) return null
        return TORONITES_NONCE.find(html, blockStart)?.groupValues?.getOrNull(1)
    }

    // ------------------------------------------------------------------ transport

    /**
     * One page GET.
     *
     * Goes through [ChannelGate] and the transport ladder because the toono pages are what the edge
     * challenges: the same URL answers 200 to a browser session and 403 to the app's fingerprint, and
     * a 403 used to read as "episode gone" downstream. The ladder keeps the browser headers below and
     * adds the cookies a warm-up leaves behind.
     */
    private suspend fun fetch(
        url: String,
        referer: String? = null,
        accept: String = ACCEPT_HTML,
    ): Page {
        val headers = headersFor(referer, accept)
        val router = router
        if (router != null) return routed(ChannelGate.fetch(router, url, headers, PAGE_TIMEOUT_MS), url)
        return direct(url, headers, form = null)
    }

    /**
     * The catalogue's "Load more" POST.
     *
     * Routed for the same reason as [fetch]: the AJAX endpoint sits on the same challenged host, and
     * the transport knows the POST path so the nonce and the page it authorises travel on one session.
     */
    private suspend fun postForm(url: String, referer: String, form: String): Page {
        val headers = headersFor(referer, ACCEPT_HTML)
        headers["Content-Type"] = FORM_CONTENT_TYPE
        val router = router
        if (router != null) {
            return routed(ChannelGate.fetch(router, url, headers, PAGE_TIMEOUT_MS, form = form), url)
        }
        return direct(url, headers, form)
    }

    /**
     * Turns a classified answer into a page.
     *
     * A refusal throws so the caller stops walking: a 403 on `/series/x/` must not be followed by a
     * probe of `/movies/x/`, which is what turned one block into two requests and a confusing 404.
     * 404/410 is returned as a page because [title] needs it to try the other route.
     */
    private fun routed(answer: Fetched, url: String): Page {
        AppLog.d("source A ${answer.describe()}")
        return when (answer.fault) {
            ChannelFault.OK, ChannelFault.DEAD, ChannelFault.OTHER -> Page(answer.code, answer.body, url)
            ChannelFault.BLOCKED -> throw ResolveException.Blocked(
                ChannelGate.blockedMessage(answer.code, answer.channel, answer.retryAfterMs),
            )
            ChannelFault.TRANSIENT -> throw ToonoException(
                "GET failed on channel=${answer.channel}: " +
                    (answer.error?.javaClass?.simpleName ?: "HTTP ${answer.code}"),
            )
        }
    }

    private fun headersFor(referer: String?, accept: String): MutableMap<String, String> {
        val headers = LinkedHashMap<String, String>(4)
        headers["User-Agent"] = USER_AGENT
        headers["Accept-Language"] = ACCEPT_LANGUAGE
        headers["Accept"] = accept
        if (referer != null) headers["Referer"] = referer
        return headers
    }

    /**
     * Raw-client path, kept as the fallback when no ladder is configured (unit tests): bounded
     * retries over transient codes and IO errors, exactly as before.
     */
    private suspend fun direct(url: String, headers: Map<String, String>, form: String?): Page {
        var lastCode = 0
        var lastError: IOException? = null

        for (attempt in 0 until FETCH_ATTEMPTS) {
            if (attempt > 0) delay(RETRY_DELAY_MS * attempt)

            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.header(name, value) }

            val request = if (form == null) {
                builder.get().build()
            } else {
                builder.post(form.toRequestBody(FORM_MEDIA_TYPE)).build()
            }

            val outcome = try {
                http.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        Page(response.code, response.body?.string().orEmpty(), url)
                    } else {
                        lastCode = response.code
                        null
                    }
                }
            } catch (error: IOException) {
                lastError = error
                AppLog.w("source A fetch failed: ${error.message}")
                null
            }

            if (outcome != null) return outcome
            if (lastCode in 200..299) break
            if (lastCode != 0 && lastCode !in RETRYABLE_CODES) break
        }

        if (lastError != null && lastCode == 0) throw lastError
        return Page(lastCode, "", url)
    }

    private fun absoluteUrl(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> BASE + value
            else -> "$BASE/$value"
        }
    }

    private fun canonicalPath(raw: String): String? {
        val tail = raw.trim()
            .removePrefix("$BASE/")
            .removePrefix(BASE.replaceFirst("https://", "http://") + "/")
            .trim('/')
        return when {
            tail.startsWith("$SERIES/") || tail.startsWith("$MOVIES/") -> tail
            else -> null
        }
    }

    private fun match(regex: Regex, input: String, group: Int = 1): String? =
        regex.find(input)?.groupValues?.getOrNull(group)?.takeIf { it.isNotBlank() }

    private class Page(val code: Int, val body: String, val url: String) {
        fun isOk(): Boolean = code in 200..299
    }

    @Volatile
    private var cachedNonce: String? = null

    private val nonceLock = Mutex()

    private companion object {
        val BASE: String by lazy { ProviderVault.url(ProviderKey.DUB_HOST_A) }
        val AJAX_ENDPOINT: String by lazy { "$BASE/wp-admin/admin-ajax.php" }
        const val SERIES = "series"
        const val MOVIES = "movies"

        /**
         * One UA for every hop. The origin nginx rejects a headless-Chrome UA with a bare 403 on
         * uncached paths (measured twice, independently), and the warm-up WebView is configured
         * with this same string, so a clearance earned there is replayable here.
         */
        const val USER_AGENT = com.animo.app.data.remote.HttpClientFactory.UA_DESKTOP
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.6"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        const val TAXONOMY_CATEGORY = "category"
        const val TAXONOMY_NONE = "none"
        const val TERM_NONE = "none"
        const val SEARCH_NONE = "none"
        const val TYPE_MIXED = "mixed"
        const val HINDI_CATEGORY_ID = "197"
        const val LANGUAGE_HINDI = "Hindi"

        /** The four language categories that the site exposes under `/category/<slug>/`. */
        val LANGUAGE_CATEGORY_SLUGS = setOf("hindi", "tamil", "telugu", "english")

        const val PAGE_SIZE = 10
        const val AJAX_ATTEMPTS = 2
        const val FETCH_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 400L
        const val HEADER_WINDOW = 4_000
        const val ARTICLE_WINDOW = 12_000
        const val STATUS_400_MARKER = "\"status\":400"

        const val AJAX_ACTION = "action_search"

        /** Matches [com.animo.app.data.remote.HttpClientFactory]'s read timeout: no new budget. */
        const val PAGE_TIMEOUT_MS = 25_000L

        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        val FORM_MEDIA_TYPE = FORM_CONTENT_TYPE.toMediaType()

        val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

        val TORONITES_NONCE = Regex("\"nonce\"\\s*:\\s*\"([0-9a-f]{8,16})\"")

        const val CARD_OPEN = "<article class=\"post"
        const val CARD_CLOSE = "</article>"
        val CARD_TITLE = Regex("<h2 class=\"entry-title\">(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        val CARD_LINK: Regex by lazy {
            Regex("href=\"${Regex.escape(BASE)}/((?:series|movies)/[^\"/]+)/\"")
        }
        val CARD_SLUG: Regex by lazy {
            Regex("href=\"${Regex.escape(BASE)}/(?:series|movies)/([^\"/]+)/\"")
        }
/**
 * The movie card template emits `<img class="trs" src="…">` while the series card emits
 * `<img src="…">`, so requiring `src` to be the first attribute silently dropped every movie cover:
 * 618 of the 633 poster-less rows in the catalogue, all of them `type=Movie`. The leading `\s` keeps
 * the match off `data-src` / `data-lazy-src`.
 */
        val CARD_POSTER = Regex("<img[^>]*?\\ssrc=\"([^\"]+)\"")
        val CARD_YEAR = Regex("<span class=\"year\">(\\d{4})</span>")
        val CARD_GENRES = Regex(
            "<li class=\"rw sm\"><span>Genres</span><span>(.*?)</span></li>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val GENRE_LABEL = Regex("aria-label=\"([^\"]+)\"")

        val OG_TITLE = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"")
        val H1_TITLE = Regex("<h1 class=\"entry-title\"[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
        val POSTER = Regex(
            "<div class=\"post-thumbnail\">\\s*<figure>\\s*<img[^>]*?\\ssrc=\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        val YEAR = Regex("<span class=\"year\">(\\d{4})</span>")
        val RATING = Regex("<span class=\"rating fa-star\"><span>([\\d.]+)</span></span>")
        val CATEGORIES = Regex(
            "<span class=\"categories\">(.*?)</span>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val CATEGORY_TAG: Regex by lazy {
            Regex("aria-label=\"([^\"]+)\" href=\"${Regex.escape(BASE)}/category/([a-z0-9-]+)/\"")
        }
        val SYNOPSIS = Regex("<div class=\"entry-content\">\\s*<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val TAG = Regex("<[^>]+>")

        val EPISODE_ROW = Regex("<li data-episode-id=\"(\\d+)\">")
        val EPISODE_LINK: Regex by lazy {
            Regex("href=\"(${Regex.escape(BASE)}/episode/[^\"]+)\"")
        }

        val PLAYER_FRAME = Regex(
            "<iframe[^>]*\\sid=\"main-player\"[^>]*\\ssrc=\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        val ANY_TREMBED_FRAME = Regex(
            "<iframe[^>]*\\ssrc=\"([^\"]*trembed[^\"]*)\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        val FALLBACK_EPISODE_ID = Regex("data-episode-id=\"(\\d+)\"")
        val EMBED_ID = Regex("https://[a-z0-9.-]+/embed/([A-Za-z0-9_-]+)")
    }
}

/**
 * Parsed toono row. Deliberately **not** `@Serializable`: every field comes from HTML, never from a
 * JSON contract, so a serialiser would only invite a decoder that cannot work.
 *
 * [id] is `<kind>/<slug>` (`series/1-happy-family-usa`, `movies/tarzan-jane`) because the two routes
 * are exclusive and [slug] alone cannot say which one to fetch; [url] turns it back into a page.
 *
 * [isHindi] means **proven**, not "probably": true when the row came from the Hindi-category
 * enumeration or when the title page carries the `hindi` category. Cards in the unfiltered
 * enumeration carry no language tags at all, so they are reported as `false` and must be re-checked
 * through [ToonoProvider.title].
 *
 * [synopsis] and [rating] are parsed only on [ToonoProvider.title]; the listing leaves them empty
 * because the AJAX cards do not expose a stable rating.
 */
data class DubTitleA(
    val id: String,
    val slug: String,
    val title: String,
    val poster: String?,
    val year: Int?,
    val isHindi: Boolean,
    val languages: List<String>,
    val genres: List<String>,
    val episodeUrls: List<String>,
    val synopsis: String = "",
    val rating: Double? = null,
) {

    /** The title page. For a movie this page is also the player, so it can go straight to `embedId`. */
    val url: String get() = "${ProviderVault.url(ProviderKey.DUB_HOST_A)}/$id/"
}

class ToonoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Cancellation-aware bridge from OkHttp's callback API. The scraper issues page-sized GETs, so a
 * coroutine must not be pinned to a thread for the duration of the body read.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response)
            }

            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        },
    )
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}
