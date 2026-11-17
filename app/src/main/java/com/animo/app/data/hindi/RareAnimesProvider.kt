package com.animo.app.data.hindi

import com.animo.app.data.remote.Normalize
import com.animo.app.data.remote.dto.AnimoJson
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * rareanimes.foo ("Rare Toons India") — the largest Hindi catalogue, network + parsing only.
 *
 * No Room, no DI, no player, no `android.*`: everything here is plain JVM so the parser can be unit
 * tested and the class can be driven from the sync worker without an Android runtime.
 *
 * ## What was measured live (2026-11-17) and is encoded below
 *
 * | fact | value |
 * |---|---|
 * | catalogue | `GET /wp-json/wp/v2/posts` → `x-wp-total: 1942`, `x-wp-totalpages: 20` |
 * | Hindi slice | `?categories=180,297` → `x-wp-total: 1715`, `x-wp-totalpages: 18` — the comma form is **OR** (`IN`), not AND |
 * | per-title | `GET /wp-json/wp/v2/posts/{id}?_embed=wp:featuredmedia` returns poster + content in one call |
 * | episode rows | inline in `content.rendered`; there is no episode CPT and no episode page |
 * | embed | `argon.razorshell.space/embed/<id>`, reached via `codedew.com/multiquality/?url=<id>` or by rewriting `/downlead/<id>` |
 * | legacy gate | `codedew.com/zipper/?url=<opaque>` still answers 200 for old posts, with a 5 s client-side timer |
 *
 * ## Two structures that the parser must survive
 *
 * Series posts repeat one `<p>Episode N (abs) – Name</p>` per episode followed by one `<p>` per
 * language (`<span style="color:#99cc00">Hindi</span> – [links]`), and some posts repeat the episode
 * heading for an "Untouched (Multi Audio)" row — rows are therefore **merged by episode number**, not
 * appended per heading. Movie posts carry no episode marker at all: `<h4>Hindi – Download</h4>` then
 * one `<h3><a>` per host, so those links land in [RareTitle.batchLinks] with a `null` episode.
 *
 * ## Deliberately absent
 *
 * No cookie jar. The catalogue, the player and even the legacy zipper's `ad_done=1` hop all answer
 * 200 without cookies; the only cookie the zipper sets is `zipper_client_id`, and a cookie-free step 2
 * was measured at exactly the same 26,638-byte body as the cookie-carrying one.
 *
 * @param log sink for one-line request/hop traces. Defaults to a no-op so this file never touches
 *   `android.util.Log`; production wiring passes `AppLog::d`.
 */
class RareAnimesProvider(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = UA_HEADLESS_CHROME,
    private val catalogIntervalMs: Long = CATALOG_INTERVAL_MS,
    private val resolveIntervalMs: Long = RESOLVE_INTERVAL_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val log: (String) -> Unit = {},
) {

    private val base: String = baseUrl.trimEnd('/')

    /**
     * Single-threaded pacer state. The crawler is serial by design (research/RAREDOWN §C3: no
     * parallel hammering), so a mutex would only serialise callers that already queue.
     */
    @Volatile
    private var lastRequestAtNanos: Long = 0L

    /** Set when Cloudflare answers with a challenge; blocks every call for [COOLDOWN_MS]. */
    private val cooldownUntil = AtomicLong(0L)

    @Volatile
    private var trace: List<String> = emptyList()

    /** Totals of the most recent catalogue page, including pages answered with `400` (past the end). */
    @Volatile
    var lastTotal: Int? = null
        private set

    @Volatile
    var lastTotalPages: Int? = null
        private set

    // ------------------------------------------------------------------ catalog

    /**
     * One catalogue page: `GET /wp-json/wp/v2/posts?per_page=..&page=..&_fields=..`.
     *
     * `per_page` above 100 is silently clamped by WordPress, so it is clamped here instead of burning
     * a request to learn that. A page past the end answers **HTTP 400**
     * (`rest_post_invalid_page_number`), not an empty `200` — that is reported as an empty page with
     * the totals intact so callers can stop without parsing an error body.
     */
    suspend fun catalogPage(page: Int, perPage: Int = DEFAULT_PER_PAGE): RareCatalogPage =
        fetchPage(page = page, perPage = perPage, categories = null)

    /** Convenience wrapper for callers that only need the rows. */
    suspend fun catalog(page: Int, perPage: Int = DEFAULT_PER_PAGE): List<RareTitle> =
        catalogPage(page, perPage).titles

    /**
     * The Hindi slice of the same catalogue (`categories=180,297`, 1715 titles / 18 pages).
     *
     * The comma form is measured to be `IN` (OR): a page that contained a post with neither category
     * would mean the filter started ANDing (or the ids drifted). Instead of failing the sync, that
     * case re-fetches both category ids separately and merges by post id — two extra requests, only
     * on the day the install changes behaviour.
     */
    suspend fun hindiCatalogPage(page: Int, perPage: Int = DEFAULT_PER_PAGE): RareCatalogPage {
        val primary = fetchPage(page = page, perPage = perPage, categories = HINDI_CATEGORIES_CSV)
        val drifted = primary.titles.filterNot { it.isHindi }
        if (drifted.isEmpty()) return primary

        log("ra hindi filter looks ANDed: ${drifted.size}/${primary.titles.size} rows carry neither 180 nor 297")
        val dub = fetchPage(page = page, perPage = perPage, categories = CATEGORY_HINDI_DUB.toString())
        val sub = fetchPage(page = page, perPage = perPage, categories = CATEGORY_HINDI_SUB.toString())

        val merged = LinkedHashMap<Int, RareTitle>(dub.titles.size + sub.titles.size)
        (dub.titles + sub.titles).forEach { merged[it.id] = it }

        val total = listOfNotNull(dub.total, sub.total).takeIf { it.size == 2 }?.sum()
        return RareCatalogPage(
            titles = merged.values.toList(),
            page = page.coerceAtLeast(1),
            total = total,
            totalPages = maxOf(dub.totalPages ?: 0, sub.totalPages ?: 0).takeIf { it > 0 },
        )
    }

    /** Convenience wrapper for callers that only need the rows. */
    suspend fun hindiCatalog(page: Int, perPage: Int = DEFAULT_PER_PAGE): List<RareTitle> =
        hindiCatalogPage(page, perPage).titles

    private suspend fun fetchPage(page: Int, perPage: Int, categories: String?): RareCatalogPage {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, MAX_PER_PAGE)

        val url = buildString {
            append(base).append("/wp-json/wp/v2/posts")
            append("?per_page=").append(safePerPage)
            append("&page=").append(safePage)
            if (categories != null) append("&categories=").append(categories)
            append("&_fields=").append(LIST_FIELDS)
        }

        val response = fetch(url, accept = ACCEPT_JSON)
        response.total?.let { lastTotal = it }
        response.totalPages?.let { lastTotalPages = it }

        if (response.code == HTTP_BAD_REQUEST) {
            log("ra catalog page $safePage: 400 (past last page) -> empty")
            return RareCatalogPage(emptyList(), safePage, response.total, response.totalPages)
        }
        if (!response.isSuccessful) {
            throw RareAnimesException("catalog page $safePage: HTTP ${response.code}")
        }

        val titles = parseList(response.body)
        log("ra catalog page $safePage: ${titles.size} titles (total=${response.total}, pages=${response.totalPages})")
        return RareCatalogPage(titles, safePage, response.total, response.totalPages)
    }

    // ------------------------------------------------------------------ detail

    /**
     * Fetch the post and parse poster, synopsis and every episode row.
     *
     * Uses `_fields` **and** `_embed=wp:featuredmedia` in one request: `_embedded` is omitted when
     * `_fields` is supplied without `_links`, and the poster lives in `_embedded`, so both are asked
     * for. The returned object always carries the [post] identity fields even when the detail call is
     * partial, so a caller can upsert it without losing the listing row.
     */
    suspend fun title(post: RareTitle): RareTitle {
        val url = when {
            post.id > 0 -> "$base/wp-json/wp/v2/posts/${post.id}?$TITLE_QUERY"
            post.slug.isNotBlank() -> "$base/wp-json/wp/v2/posts?slug=${encodeQueryValue(post.slug)}&per_page=1&$TITLE_QUERY"
            else -> throw RareAnimesException("title(): post has neither id nor slug")
        }

        val response = fetch(url, accept = ACCEPT_JSON)
        if (!response.isSuccessful) {
            throw RareAnimesException("title (${post.id}): HTTP ${response.code}")
        }

        val element = try {
            AnimoJson.parseToJsonElement(response.body)
        } catch (error: Exception) {
            throw RareAnimesException("title (${post.id}): unparseable JSON", error)
        }

        val obj = when (element) {
            is JsonArray -> element.firstOrNull() as? JsonObject
            is JsonObject -> element
            else -> null
        } ?: throw RareAnimesException("title (${post.id}): empty payload")

        return parseDetail(obj, post)
    }

    // ------------------------------------------------------------------ embed

    /**
     * Resolve any row link to the argon embed URL.
     *
     * Measured shapes, in the order they are handled:
     * 1. `*.multiquality.click/...` — the host is **NXDOMAIN** today (`swift.multiquality.click`
     *    resolves nowhere; 82 of ~119 sampled rows point at it) but its id space is shared with the
     *    player, so the trailing id is rebuilt on argon.
     * 2. `/<anything>/embed/<id>` — already an embed, returned as-is.
     * 3. `/<anything>/downlead/<id>` — the download landing page of the same video; the id matches
     *    the embed id exactly (verified on `NRf7nNo5GJ2eBqO` and `srCZj4T7ft9YFr3`).
     * 4. `codedew.com/multiquality/?url=<id>` — 200 with `<iframe id="playerIframe" src=…>`.
     * 5. `codedew.com/zipper/?url=<opaque>` (old posts only) — the "Step N of 3" gate. Its 5-second
     *    countdown is pure client JS: the next hop is in `data-href` on arrival, so it is followed
     *    immediately and never waited for.
     *
     * Any other host (hubcloud, pixeldrain, mega, gofile, mediafire, the batch `zipcloud` redirector)
     * is a file host, not a player: [RareEmbedUnavailableException] tells the caller to open it in a
     * browser instead of trying to stream it.
     */
    suspend fun embedUrl(zipperUrl: String): String {
        var url = zipperUrl.trim()
        if (url.isEmpty()) throw RareAnimesException("embedUrl(): empty url")

        val hops = mutableListOf<String>()
        try {
            var hop = 0
            while (hop < MAX_EMBED_HOPS) {
                hop += 1
                val parsed = url.toHttpUrlOrNull()
                    ?: throw RareAnimesException("embedUrl(): not an absolute url: $url")
                val host = parsed.host.lowercase()
                val path = parsed.encodedPath
                hops += "hop $hop: $host$path"

                // 1. Dead player mirror — same id space as the live player.
                if (host == MULTIQUALITY_CLICK || host.endsWith(".$MULTIQUALITY_CLICK")) {
                    val id = lastSegment(path)
                        ?: throw RareEmbedUnavailableException(host, "no video id in $url")
                    hops += "nxdomain player host -> argon rewrite"
                    return argonEmbed(id)
                }

                // 2. Already the player embed.
                if (path.contains("/embed/", ignoreCase = true)) {
                    hops += "already an embed"
                    return url
                }

                // 3. Download landing page carrying the same video id.
                if (path.contains("/downlead/", ignoreCase = true)) {
                    val id = lastSegment(path)
                        ?: throw RareEmbedUnavailableException(host, "no video id in $url")
                    hops += "downlead -> argon embed"
                    return argonEmbed(id)
                }

                // 4. Player wrapper page.
                if (path.contains("/multiquality", ignoreCase = true)) {
                    val response = fetch(url, accept = ACCEPT_HTML, referer = RARE_REFERER, intervalMs = resolveIntervalMs)
                    hops += "multiquality ${response.code} ${response.body.length}b"
                    if (!response.isSuccessful) {
                        throw RareAnimesException("embed: multiquality answered HTTP ${response.code}")
                    }
                    val frame = playerFrame(response.body)
                        ?: throw RareEmbedUnavailableException(host, "no #playerIframe on $url")
                    url = absolute(response.finalUrl, frame)
                    continue
                }

                // 5. Legacy ad gate. data-href already holds the next hop; no timer is honoured.
                if (path.contains("/zipper", ignoreCase = true)) {
                    val response = fetch(url, accept = ACCEPT_HTML, referer = RARE_REFERER, intervalMs = resolveIntervalMs)
                    hops += "zipper ${response.code} ${response.body.length}b"
                    if (!response.isSuccessful) {
                        throw RareAnimesException("embed: zipper answered HTTP ${response.code} (expired token?)")
                    }
                    val next = dataHref(response.body) ?: jsRedirect(response.body)
                        ?: throw RareAnimesException("embed: zipper page carried no data-href")
                    url = absolute(response.finalUrl, next)
                    continue
                }

                // 6. Batch archive — a file host hop, never a stream.
                if (path.contains("/zipcloud", ignoreCase = true)) {
                    hops += "batch archive -> not a player"
                    throw RareEmbedUnavailableException(host, "batch download archive, no streaming player")
                }

                throw RareEmbedUnavailableException(host, "host is not a player")
            }

            throw RareAnimesException("embedUrl(): exceeded $MAX_EMBED_HOPS hops for $zipperUrl")
        } finally {
            trace = hops
            log("ra embed ${hops.joinToString(" | ")}")
        }
    }

    /** The hop trail of the most recent [embedUrl] call — for the debug screen and bug reports. */
    fun resolveTrace(): List<String> = trace

    /** Clears a Cloudflare cooldown. Only tests and an explicit user retry should call this. */
    fun resetCooldown() {
        cooldownUntil.set(0L)
    }

    // ------------------------------------------------------------------ transport

    /**
     * One GET with pacing, bounded retries and a Cloudflare kill switch.
     *
     * Retryable answers (408/425/429/5xx) honour `Retry-After` and then fall back to exponential
     * backoff. A challenge page is **not** retried: it parks every call for 24 h and throws, because
     * a retry storm is how a soft block becomes an IP ban (research/RAREDOWN §C3.7).
     */
    private suspend fun fetch(
        url: String,
        accept: String,
        referer: String? = null,
        intervalMs: Long = catalogIntervalMs,
    ): RareResponse {
        checkCooldown()

        var attempt = 0
        var lastCode = 0
        var lastError = "no attempt"

        while (attempt < maxAttempts) {
            pace(intervalMs)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .apply { if (referer != null) header("Referer", referer) }
                .build()

            val startedAt = System.nanoTime()
            val response = try {
                http.newCall(request).awaitCall()
            } catch (error: IOException) {
                lastError = error.message ?: error.javaClass.simpleName
                if (attempt == maxAttempts - 1) {
                    throw RareAnimesException("GET $url failed after ${attempt + 1} attempts: $lastError", error)
                }
                val wait = backoffMs(attempt)
                log("ra io error ($lastError) -> retry in ${wait}ms (${attempt + 1}/$maxAttempts)")
                delay(wait)
                attempt += 1
                continue
            }

            val code = response.code
            val body = try {
                response.body?.string().orEmpty()
            } catch (error: IOException) {
                lastError = "body read failed: ${error.message}"
                ""
            } finally {
                response.close()
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            log(
                "ra $code ${response.request.url} ${body.length}b ${elapsedMs}ms " +
                    "cf=${response.header("cf-cache-status") ?: "-"}",
            )

            if (looksChallenged(response.header("cf-mitigated"), body)) {
                enterCooldown("cloudflare challenge on $url")
                throw RareAnimesBlockedException(code, "cloudflare challenge on $url")
            }

            if (code in RETRYABLE_CODES && attempt < maxAttempts - 1) {
                lastCode = code
                val wait = retryAfterMs(response.header("Retry-After")) ?: backoffMs(attempt)
                log("ra $code -> retry in ${wait}ms (${attempt + 1}/$maxAttempts)")
                delay(wait)
                attempt += 1
                continue
            }

            return RareResponse(
                code = code,
                body = body,
                finalUrl = response.request.url.toString(),
                total = response.header(HEADER_TOTAL)?.trim()?.toIntOrNull(),
                totalPages = response.header(HEADER_TOTAL_PAGES)?.trim()?.toIntOrNull(),
            )
        }

        throw RareAnimesException("GET $url failed (HTTP $lastCode) after $maxAttempts attempts: $lastError")
    }

    /** Serial spacing between request starts. Skipped when the configured interval is zero. */
    private suspend fun pace(intervalMs: Long) {
        if (intervalMs <= 0L) return
        val waitMs = (lastRequestAtNanos + intervalMs * 1_000_000L - System.nanoTime()) / 1_000_000L
        if (waitMs > 0L) delay(waitMs)
        lastRequestAtNanos = System.nanoTime()
    }

    private fun checkCooldown() {
        val until = cooldownUntil.get()
        if (until == 0L) return
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) {
            cooldownUntil.compareAndSet(until, 0L)
            return
        }
        throw RareAnimesBlockedException(0, "rareanimes cooling down for another ${remaining / 1000}s")
    }

    private fun enterCooldown(reason: String) {
        val until = System.currentTimeMillis() + COOLDOWN_MS
        cooldownUntil.set(until)
        log("ra KILL SWITCH: $reason — no calls for ${COOLDOWN_MS / 3_600_000}h")
    }

    private fun backoffMs(attempt: Int): Long {
        val base = BASE_BACKOFF_MS shl attempt.coerceIn(0, 4)
        val jitter = Random.nextLong(0L, BACKOFF_JITTER_MS)
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun retryAfterMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = value.toLongOrNull()
        if (seconds != null) return (seconds * 1000L).coerceIn(0L, MAX_BACKOFF_MS)
        return null
    }

    private fun looksChallenged(cfMitigated: String?, body: String): Boolean {
        if (cfMitigated != null && cfMitigated.contains("challenge", ignoreCase = true)) return true
        return CHALLENGE_MARKERS.any { body.contains(it) }
    }

    // ------------------------------------------------------------------ response

    private data class RareResponse(
        val code: Int,
        val body: String,
        val finalUrl: String,
        val total: Int?,
        val totalPages: Int?,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    // ------------------------------------------------------------------ parsing: JSON

    private fun parseList(body: String): List<RareTitle> {
        val element = try {
            AnimoJson.parseToJsonElement(body)
        } catch (error: Exception) {
            throw RareAnimesException("catalog: unparseable JSON", error)
        }
        val array = element as? JsonArray
            ?: throw RareAnimesException("catalog: expected a JSON array, got ${element::class.simpleName}")

        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            RareTitle(
                id = id,
                slug = obj.text("slug").orEmpty(),
                title = obj.nestedText("title", "rendered").orEmpty(),
                link = obj.text("link").orEmpty(),
                date = obj.text("date").orEmpty(),
                categories = obj.intArray("categories"),
                poster = "",
                synopsis = RareAnimesParser.plainText(obj.nestedText("excerpt", "rendered").orEmpty()),
            )
        }
    }

    private fun parseDetail(obj: JsonObject, fallback: RareTitle): RareTitle {
        val content = obj.nestedText("content", "rendered").orEmpty()
        val excerpt = obj.nestedText("excerpt", "rendered").orEmpty()
        val parsed = RareAnimesParser.parse(content)

        val poster = RareAnimesParser.poster(obj) ?: RareAnimesParser.firstImage(content)
        val synopsis = RareAnimesParser.synopsis(content, excerpt)

        return RareTitle(
            id = obj.int("id") ?: fallback.id,
            slug = obj.text("slug") ?: fallback.slug,
            title = obj.nestedText("title", "rendered")?.let { Normalize.cleanText(it) }
                ?: fallback.title,
            link = obj.text("link") ?: fallback.link,
            date = obj.text("date") ?: fallback.date,
            categories = obj.intArray("categories").ifEmpty { fallback.categories },
            poster = poster,
            synopsis = synopsis,
            episodes = parsed.episodes,
            batchLinks = parsed.batchLinks,
        )
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://www.rareanimes.foo"

        /** The UA that returned 200 on every hop of the measured crawl. */
        const val UA_HEADLESS_CHROME =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "HeadlessChrome/156.0.0.0 Safari/537.36"

        const val ACCEPT_JSON = "application/json, text/plain, */*"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.6"
        const val RARE_REFERER = "https://www.rareanimes.foo/"

        /** 1942 posts / 100 per page = 20 pages; WordPress clamps anything above 100. */
        const val DEFAULT_PER_PAGE = 100
        const val MAX_PER_PAGE = 100

        const val CATEGORY_HINDI_DUB = 180
        const val CATEGORY_HINDI_SUB = 297
        const val HINDI_CATEGORIES_CSV = "180,297"

        const val LIST_FIELDS = "id,slug,title,link,date,categories,featured_media,excerpt"

        /**
         * `_embedded` is dropped when `_fields` omits `_links`, and the poster lives in `_embedded`,
         * so both are requested together with the content.
         */
        const val TITLE_QUERY =
            "_fields=id,slug,title,link,date,categories,featured_media,content,excerpt,_links,_embedded" +
                "&_embed=wp:featuredmedia"

        const val HEADER_TOTAL = "x-wp-total"
        const val HEADER_TOTAL_PAGES = "x-wp-totalpages"
        const val HTTP_BAD_REQUEST = 400

        /** Measured cadence for catalogue work: 1 request / 3 s, no concurrency. */
        const val CATALOG_INTERVAL_MS = 3_000L

        /** Embeds are resolved at tap time; browser-like spacing keeps the 4 hops under 2 s. */
        const val RESOLVE_INTERVAL_MS = 350L

        const val MAX_ATTEMPTS = 3
        const val MAX_EMBED_HOPS = 5

        const val BASE_BACKOFF_MS = 1_000L
        const val BACKOFF_JITTER_MS = 750L
        const val MAX_BACKOFF_MS = 30_000L

        const val COOLDOWN_MS = 24L * 60L * 60L * 1000L

        const val MULTIQUALITY_CLICK = "multiquality.click"
        const val ARGON_BASE = "https://argon.razorshell.space"
        const val ARGON_EMBED_PREFIX = "$ARGON_BASE/embed/"

        val RETRYABLE_CODES = setOf(403, 408, 425, 429, 500, 502, 503, 504)

        /** Shapes Cloudflare serves instead of the page; a retry against any of them is pointless. */
        val CHALLENGE_MARKERS = listOf(
            "Just a moment...",
            "Attention Required!",
            "__cf_chl",
            "cf-challenge",
            "challenge-platform/scripts/jsd",
        )

        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{3,64}$")
        val DATA_HREF = Regex("data-href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val JS_REDIRECT = Regex(
            "(?:window\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        )
        val PLAYER_FRAME_ID = Regex(
            "<iframe[^>]*\\bid\\s*=\\s*[\"']playerIframe[\"'][^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val PLAYER_FRAME_ANY = Regex(
            "<iframe[^>]*\\bsrc\\s*=\\s*[\"']([^\"']*(?:argon\\.razorshell\\.space|multiquality)[^\"']*)[\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun argonEmbed(id: String): String {
            if (!VIDEO_ID.matches(id)) {
                throw RareAnimesException("embed: implausible video id '$id'")
            }
            return ARGON_EMBED_PREFIX + id
        }

        fun lastSegment(path: String): String? =
            path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }

        fun absolute(base: String, reference: String): String {
            if (reference.startsWith("http", ignoreCase = true)) return reference
            val resolved = base.toHttpUrlOrNull()?.resolve(reference)?.toString()
            return resolved ?: reference
        }

        fun dataHref(html: String): String? =
            DATA_HREF.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        fun jsRedirect(html: String): String? =
            JS_REDIRECT.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http", true) }

        fun playerFrame(html: String): String? =
            PLAYER_FRAME_ID.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: PLAYER_FRAME_ANY.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        fun encodeQueryValue(raw: String): String =
            raw.replace(" ", "%20").replace("#", "%23")
    }
}

// ---------------------------------------------------------------------- model

/**
 * One catalogue page plus the `x-wp-total` / `x-wp-totalpages` values the server reported.
 *
 * [total] and [totalPages] are nullable on purpose: WordPress omits both on an error answer, and a
 * caller that planned a 20-page walk must not be told "0 pages" when the header was simply absent.
 */
data class RareCatalogPage(
    val titles: List<RareTitle>,
    val page: Int,
    val total: Int?,
    val totalPages: Int?,
) {
    /** True when this page was past the end: WordPress answers `400`, reported here as an empty page. */
    val isEmpty: Boolean get() = titles.isEmpty()
}

/**
 * One rareanimes post, as the REST API describes it.
 *
 * [id] is the WordPress post id and is the only stable key: the slug changes when the title is edited
 * and the article was measured to be reachable as `?p=<id>` too. [isHindi] is read from the category
 * ids (180 dub / 297 sub), never from tags — the tag vocabulary is 8,783 entries of noise.
 *
 * [episodes] is empty until [RareAnimesProvider.title] has run; [batchLinks] holds the links that
 * belong to no single episode (movie posts, "Download All Episodes at Once" archives).
 */
data class RareTitle(
    val id: Int,
    val slug: String,
    val title: String,
    val link: String,
    val date: String,
    val categories: List<Int> = emptyList(),
    val poster: String = "",
    val synopsis: String = "",
    val episodes: List<RareEpisode> = emptyList(),
    val batchLinks: List<RareLink> = emptyList(),
) {

    /** Flat view over every parsed row, in post order. */
    val episodeLinks: List<RareLink> get() = episodes.flatMap { it.links } + batchLinks

    /** Only the rows a player can open: `argon …/embed/`, `/downlead/` and the multiquality hosts. */
    val streamLinks: List<RareLink> get() = episodeLinks.filter { it.isPlayerLink }

    val isHindi: Boolean get() = categories.any { it in HINDI_CATEGORY_IDS }

    val isMovie: Boolean get() = CATEGORY_MOVIES in categories

    companion object {
        /** Verified: `categories=180,297` → 1,715 posts (1,693 dub + 34 sub − 12 dual-tagged). */
        val HINDI_CATEGORY_IDS: Set<Int> = setOf(180, 297)

        /** `movies` — 613 posts (verified via `/wp-json/wp/v2/categories?slug=movies`). */
        const val CATEGORY_MOVIES = 162

        /** `animes` — 1,154 posts; the split between anime and cartoon is not exposed as a category. */
        const val CATEGORY_ANIMES = 1
    }
}

/**
 * One episode of a series post.
 *
 * [number] is the in-season number from `Episode 01 …`, [absolute] the parenthesised overall number
 * that long-running shows carry (`Episode 01 (373)`). [name] keeps the episode title when the post
 * has one; the "Untouched (Multi Audio)" duplicate headings are discarded, they are a second set of
 * rows for the same episode.
 */
data class RareEpisode(
    val number: Int,
    val absolute: Int? = null,
    val name: String = "",
    val links: List<RareLink> = emptyList(),
)

/**
 * One anchor from an episode row.
 *
 * [episode] is `null` for movie posts and batch archives. [language] comes from the coloured
 * `<span>` that precedes the row ("Hindi FanDub", "Tamil", …) or from a `<h4>Hindi – Download</h4>`
 * heading; [quality] is only ever a *declared* string scraped from the label — the site publishes one
 * multi-quality file per episode, so it is never a per-file attribute.
 */
data class RareLink(
    val episode: Int?,
    val language: String,
    val label: String,
    val host: String,
    val url: String,
    val quality: String = "",
) {

    /** True when [url] can reach a player (or can be rewritten into one) — see [RareAnimesProvider.embedUrl]. */
    val isPlayerLink: Boolean
        get() = host.endsWith("multiquality.click") ||
            url.contains("/embed/", ignoreCase = true) ||
            url.contains("/downlead/", ignoreCase = true) ||
            url.contains("/multiquality", ignoreCase = true) ||
            url.contains("/zipper", ignoreCase = true)
}

// ---------------------------------------------------------------------- parser

/** Result of parsing one post body: numbered episodes plus the rows that belong to no episode. */
data class RareContent(
    val episodes: List<RareEpisode>,
    val batchLinks: List<RareLink>,
)

/**
 * The content parser, kept separate from the network class so it is testable on the JVM with a saved
 * `content.rendered` string.
 *
 * Everything is regex + manual scan: there is no Jsoup on the classpath, and the two layouts it has to
 * read are simple enough that a DOM would only add a dependency. Entities are unescaped up front
 * (`&#8211;` is the dash in every episode heading, `&amp;` appears inside hrefs), which is safe here
 * because the post body contains no escaped markup.
 */
object RareAnimesParser {

    /**
     * Measured structures:
     * - series: `<p>Episode 01 (373) – Team 7 Assemble!</p>` then `<p><span style="color:#99cc00">Hindi</span> – [links]</p>`
     * - movie: `<h4>Hindi – Download</h4>` then one `<h3><a href=…>HubCloud</a></h3>` per host
     * - batch: `<h3>ZIP – Cloud</h3>` blocks before the first episode heading
     *
     * Episode headings are merged by number: a long-running post repeats the heading for an
     * "Untouched (Multi Audio)" row, and appending per heading turned 21 episodes into 39.
     */
    fun parse(sourceHtml: String): RareContent {
        if (sourceHtml.isBlank()) return RareContent(emptyList(), emptyList())

        val html = Normalize.unescapeHtml(sourceHtml)
        val episodes = LinkedHashMap<Int, MutableEpisode>()
        val batch = mutableListOf<RareLink>()

        var current: MutableEpisode? = null
        var language: String? = null
        val fallbackLanguage = metadataLanguage(html)

        for (block in BLOCK.findAll(html)) {
            // Index access, not `groups["body"]`: the named overload goes through
            // `Matcher#start(String)`, which is API 26 while this app ships to minSdk 24.
            val body = block.groups[2]?.value ?: continue
            val text = plainText(body)

            val heading = if (text.startsWith("episode", ignoreCase = true)) EPISODE.find(text) else null
            if (heading != null) {
                val number = heading.groupValues[1].toIntOrNull()
                if (number != null) {
                    val absolute = heading.groupValues[2].toIntOrNull()
                    val episode = episodes.getOrPut(number) { MutableEpisode(number) }
                    if (episode.absolute == null) episode.absolute = absolute
                    val name = heading.groupValues[3].trim()
                    if (name.isNotEmpty() &&
                        !name.startsWith("untouched", ignoreCase = true) &&
                        episode.name.isEmpty()
                    ) {
                        episode.name = name
                    }
                    current = episode
                }
                language = null
            } else {
                HEADING_LANGUAGE.matchEntire(text)?.let { language = normalizeLanguage(it.groupValues[1]) }
            }

            var blockLanguage = language
            for (span in COLOUR_SPAN.findAll(body)) {
                val spanText = normalizeLanguage(span.groupValues[1])
                if (isLanguage(spanText)) {
                    blockLanguage = spanText
                    language = spanText
                    break
                }
            }
            if (blockLanguage == null && text.contains("multi audio", ignoreCase = true)) {
                blockLanguage = MULTI_AUDIO
            }

            for (anchor in ANCHOR.findAll(body)) {
                val url = anchor.groupValues[1].trim()
                if (!url.startsWith("http", ignoreCase = true)) continue
                val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: continue
                if (host.endsWith("rareanimes.foo") || host.endsWith("rareanimes.fans")) continue

                val label = plainText(anchor.groupValues[2])
                val resolvedLanguage = (blockLanguage ?: fallbackLanguage).orEmpty()
                val link = RareLink(
                    episode = current?.number,
                    language = resolvedLanguage,
                    label = label,
                    host = host,
                    url = url,
                    quality = qualityOf(label, resolvedLanguage),
                )

                val target = current
                if (target != null) target.links += link else batch += link
            }
        }

        return RareContent(
            episodes = episodes.values.map { it.freeze() }.sortedBy { it.number },
            batchLinks = batch,
        )
    }

    /**
     * Poster from `_embedded["wp:featuredmedia"]`, preferring a web-sized variant over the 2560×1440
     * master. Falls back to the media's own `source_url`, then to the first inline `<img>` of the post.
     */
    fun poster(post: JsonObject): String? {
        val media = (post["_embedded"] as? JsonObject)
            ?.get("wp:featuredmedia") as? JsonArray
        val first = media?.firstOrNull() as? JsonObject ?: return null

        val sizes = (first["media_details"] as? JsonObject)?.get("sizes") as? JsonObject
        if (sizes != null) {
            for (key in POSTER_SIZE_ORDER) {
                val candidate = (sizes[key] as? JsonObject)?.text("source_url")
                if (!candidate.isNullOrBlank()) return candidate
            }
        }
        return first.text("source_url")?.takeIf { it.isNotBlank() }
    }

    /** First inline image, used when the post has no featured media. */
    fun firstImage(html: String): String {
        if (html.isBlank()) return ""
        return IMAGE.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }.orEmpty()
    }

    /**
     * The "Synopsis" span, else the excerpt with markup stripped. Their prose is copyrighted; callers
     * that display it must attribute the source page (see the module's risk notes).
     */
    fun synopsis(contentHtml: String, excerptHtml: String): String {
        if (contentHtml.isNotBlank()) {
            val marker = SYNOPSIS.find(contentHtml)
            if (marker != null) {
                val rest = contentHtml.substring(marker.range.last + 1)
                val paragraph = rest.substringBefore("</p>", missingDelimiterValue = rest.take(600))
                val cleaned = plainText(paragraph).trimStart(':', '–', '-', ' ')
                if (cleaned.isNotEmpty()) return cleaned
            }
        }
        return plainText(excerptHtml)
    }

    /** Visible text of a fragment: tags to spaces, entities decoded, whitespace collapsed. */
    fun plainText(raw: String): String =
        WHITESPACE.replace(
            TAG.replace(Normalize.unescapeHtml(raw), " ").replace('\u00A0', ' '),
            " ",
        ).trim()

    // ------------------------------------------------------------------ internals

    private fun isLanguage(candidate: String): Boolean {
        val lowered = candidate.trim().lowercase()
        if (lowered.isEmpty()) return false
        return LANGUAGE_WORDS.any { lowered.startsWith(it) }
    }

    /**
     * `Hindi – Download` (movie heading), `Hindi ` (episode row) and `Hindi FanDub` all mean a
     * language; only the first is a heading and must not leak its `– Download` suffix into the model.
     */
    private fun normalizeLanguage(raw: String): String =
        plainText(raw)
            .substringBefore("–")
            .substringBefore("—")
            .substringBefore(" - ")
            .substringBefore(":")
            .trim()

    private fun qualityOf(label: String, language: String): String {
        val match = QUALITY.find("$label $language") ?: return ""
        return match.groupValues[1] + "p"
    }

    /** `🔊Language: <span>Hindi</span>` — the post-level default for rows without their own span. */
    private fun metadataLanguage(html: String): String? {
        val raw = METADATA_LANGUAGE.find(html)?.groupValues?.getOrNull(1) ?: return null
        val candidate = normalizeLanguage(raw)
        return candidate.takeIf { isLanguage(it) }
    }

    private class MutableEpisode(val number: Int) {
        var absolute: Int? = null
        var name: String = ""
        val links: MutableList<RareLink> = mutableListOf()

        fun freeze(): RareEpisode = RareEpisode(number, absolute, name, links.toList())
    }

    private const val MULTI_AUDIO = "Multi Audio"

    /** `<p>`, `<h1>`–`<h6>` and `<li>` are the only containers the post body uses for a row. */
    private val BLOCK = Regex(
        "<(?<tag>p|h[1-6]|li)\\b[^>]*>(?<body>.*?)</\\k<tag>>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val EPISODE = Regex(
        "^\\s*Episode\\s*(\\d{1,4})\\s*(?:\\(\\s*(\\d{1,4})\\s*\\))?\\s*(?:[–—\\-:|]\\s*)?(.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<h4>Hindi – Download</h4>`: the only language marker on movie posts. */
    private val HEADING_LANGUAGE = Regex(
        "^([A-Za-z][\\w /&+.\\-']{1,30}?)\\s*[–—-]\\s*Download\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val ANCHOR = Regex(
        "<a\\b[^>]*?\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val COLOUR_SPAN = Regex(
        "<span\\b[^>]*\\bstyle\\s*=\\s*[\"'][^\"']*color\\s*:[^\"']*[\"'][^>]*>(.*?)</span>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val IMAGE = Regex(
        "<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    private val SYNOPSIS = Regex("Synopsis\\s*(?:<[^>]+>\\s*)*:?", RegexOption.IGNORE_CASE)

    private val METADATA_LANGUAGE = Regex(
        "Language\\s*:?\\s*(?:<[^>]+>\\s*)*([^<]{2,48})",
        RegexOption.IGNORE_CASE,
    )

    private val QUALITY = Regex("\\b(360|480|540|720|1080|2160)\\s*p\\b", RegexOption.IGNORE_CASE)

    private val TAG = Regex("<[^>]+>")

    private val WHITESPACE = Regex("\\s+")

    /** Ordered by usefulness for a phone list: 768 px is the smallest variant that still looks sharp. */
    private val POSTER_SIZE_ORDER = listOf("medium_large", "large", "herald-lay-b", "medium", "full", "thumbnail")

    /** Prefixes of the language spans the site actually uses; the colour is ignored on purpose. */
    private val LANGUAGE_WORDS = listOf(
        "hindi", "tamil", "telugu", "bengali", "malayalam", "urdu", "english",
        "japanese", "korean", "punjabi", "kannada", "marathi", "gujarati", "multi audio",
    )
}

// ---------------------------------------------------------------------- JSON helpers

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.nestedText(key: String, nested: String): String? =
    (this[key] as? JsonObject)?.text(nested)

private fun JsonObject.intArray(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()

// ---------------------------------------------------------------------- errors

/** Transport or parse failure on rareanimes.foo. Extends [IOException] like every other provider here. */
open class RareAnimesException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The site stopped answering: a Cloudflare challenge, or a call made while the 24 h cooldown is
 * active. The sync layer must abort the run, never retry in a loop.
 */
class RareAnimesBlockedException(val status: Int, message: String) : IOException(message)

/**
 * The row is a file host (hubcloud, pixeldrain, mega, gofile, mediafire) or a batch archive, so there
 * is no embed to return. Callers should open [host] in a browser instead of trying to stream it.
 */
class RareEmbedUnavailableException(val host: String, message: String) : RareAnimesException(message)

/**
 * Cancellation-aware bridge from OkHttp's callback API: a scraped page is up to 250 KB, so a coroutine
 * must not pin a thread for the body read.
 */
private suspend fun Call.awaitCall(): Response = suspendCancellableCoroutine { continuation ->
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
