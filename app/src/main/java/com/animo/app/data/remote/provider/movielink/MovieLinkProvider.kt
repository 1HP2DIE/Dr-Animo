package com.animo.app.data.remote.provider.movielink

import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.data.remote.Normalize
import com.animo.app.data.remote.provider.AnimeProvider
import com.animo.app.data.remote.provider.PlaybackSource
import com.animo.app.data.remote.provider.ProviderEpisode
import com.animo.app.data.remote.provider.ProviderHit
import com.animo.app.data.remote.provider.ProviderResolveException
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.StreamContainer
import com.animo.app.player.StreamResolver
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Second catalogue: a Bangla-centric index of films, series, dramas and anime.
 *
 * ## Why this class looks like a crawler and not like [com.animo.app.data.remote.provider.AnikotoProvider]
 * The index has no JSON API. Everything it knows is server-rendered HTML: a paged listing
 * (`/page/N`, 30 cards), a server-side search (`/search?q=`), and one detail page per title that
 * carries the whole record — header, meta row, storyline, JSON-LD and the `/p/<token>` player.
 * There is no separate episode page, so a series' episode list is parsed from the same detail page.
 *
 * ## Mirror rotation is why ids are host-relative
 * The host rotates. [indexHost] is the only place a host exists (the caller passes it from the
 * encrypted vault), and every id this class hands out is the **path** of the page
 * (`"movie/<slug>"`, `"series/<slug>"`). A catalogue row therefore survives a mirror move, and a
 * stale absolute link can never be the reason a 500-row sync has to be redone.
 *
 * ## Cloudflare
 * A plain request with a desktop browser UA answers 200; a bare `curl` UA answers 403. That is the
 * entire protection — there is no JS challenge in the way, so no headless browser is needed. A 403
 * is treated as **terminal** (a challenge page can be answered by every retry and still be a
 * challenge), while 429/5xx are retried with backoff.
 *
 * ## One audio track
 * The catalogue publishes a single track per title (the original Bangla audio). [ProviderEpisode]
 * always carries it in `subUrl` — the same convention the Hindi providers use — and `dubUrl` is
 * `null`. [resolve] serves that one link whatever the caller asked for, but tags the resulting
 * [PlaybackSource] as [AudioTrack.SUB] rather than echoing the request: a "dub" badge on a file
 * that has no dub is a lie the player would then show on screen.
 *
 * @param indexHost index origin, e.g. `https://<current-mirror>`. Never hardcoded here.
 * @param resolverProvider lazy resolver: the player module is compiled separately, and this class
 *   must not know the player protocol.
 * @param minIntervalMs spacing between request starts. The listing is 529 pages; a browser-like
 *   cadence is what keeps a full sync from looking like an attack. Pass `0` to disable.
 * @param log one-line trace sink. Defaults to a no-op so this file never touches `android.util.Log`
 *   and can be driven from a JVM test.
 */
class MovieLinkProvider(
    private val client: OkHttpClient,
    indexHost: String,
    private val resolverProvider: () -> StreamResolver,
    private val userAgent: String = HttpClientFactory.UA_DESKTOP,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val log: (String) -> Unit = {},
) : AnimeProvider {

    override val id: String = PROVIDER_ID

    /**
     * Parsed once: every URL is built on top of it, and a host that is not a valid absolute URL is a
     * provisioning error that must degrade to "provider unavailable", not crash at start-up.
     */
    private val origin: HttpUrl? = indexHost.trim().trimEnd('/').toHttpUrlOrNull()

    private val base: String = origin?.toString()?.trimEnd('/').orEmpty()

    /** False when the vault slot was empty or malformed. The UI can hide the provider instead of failing. */
    val isConfigured: Boolean get() = origin != null

    private val paceLock = Mutex()

    @Volatile
    private var lastStartedAtNanos: Long = 0L

    // ------------------------------------------------------------------ AnimeProvider

    /**
     * Server-side search. The index answers one page of ~21 results and has no cursor, so a `page`
     * above 1 returns an empty list instead of re-serving page 1 — a caller looping pages must be
     * able to stop.
     */
    override suspend fun search(q: String, page: Int): List<ProviderHit> =
        searchPage(q, page).getOrElse { error ->
            log("search '$q' failed: ${error.message}")
            emptyList()
        }

    /** Interface-shaped [search] that keeps the typed failure (blocked vs. empty) for the caller. */
    suspend fun searchPage(q: String, page: Int = 1): Result<List<ProviderHit>> {
        val query = Normalize.cleanText(q)
        if (query.isEmpty()) return Result.success(emptyList())
        if (page > 1) return Result.success(emptyList())

        val host = origin ?: return Result.failure(MovieLinkException("index host is not configured"))
        val url = host.newBuilder().addPathSegment(SEARCH_PATH).addQueryParameter("q", query).build()

        val html = fetch(url).getOrElse { return Result.failure(it) }
        val hits = hitsOf(MovieLinkParser.cards(html))
        log("search '$query': ${hits.size} hits")
        return Result.success(hits)
    }

    /**
     * Episode rows — which for this index means one of two shapes:
     * - **film**: the single `/p/<token>` of the page, numbered 1 and titled with the quality label;
     * - **series/drama/anime**: one row per button of the `#mlbdEpisodeDownloads` grid.
     *
     * A failure is reported as an empty list (the interface has no Result), with the reason logged;
     * [detail] is the typed entry point for a caller that needs to tell "blocked" from "no episodes".
     */
    override suspend fun episodes(seriesId: String): List<ProviderEpisode> {
        val detail = detail(seriesId).getOrElse { error ->
            log("episodes($seriesId) failed: ${error.message}")
            return emptyList()
        }

        val entries = entriesOf(detail)
        if (entries.isEmpty()) log("$seriesId published no playable entry")
        return entries
    }

    override suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack): PlaybackSource {
        val link = episode.subUrl?.trim().orEmpty()
        if (link.isEmpty()) {
            throw ProviderResolveException("no player link for ${episode.seriesId}#${episode.number}")
        }

        // The resolver is asked for the real track, not the requested one: there is only one file,
        // and asking an audio-aware resolver for a dub it will not find is how the link gets rejected.
        val stream = resolverProvider().resolve(link, AudioTrack.SUB).getOrElse { error ->
            throw ProviderResolveException("resolver: ${error.message}", error)
        }
        if (audio == AudioTrack.DUB) {
            log("${episode.seriesId}#${episode.number} has no dub; serving the original track")
        }

        return PlaybackSource(
            masterUrl = stream.masterUrl,
            // The `/p/` player expects the catalogue as its referer; the resolver may know better.
            referer = stream.referer.ifBlank { link },
            userAgent = stream.userAgent.ifBlank { userAgent },
            audio = AudioTrack.SUB,
            isHls = stream.container == StreamContainer.HLS,
        )
    }

    // ------------------------------------------------------------------ catalogue

    /**
     * One listing page: `GET {host}/page/{N}`.
     *
     * [MovieLinkParser.MAX_PAGE] is the measured end of the catalogue (529 pages × 30 cards). A page
     * past it is clamped rather than requested, so a sync that drifts cannot turn into a stream of
     * 404s.
     */
    suspend fun cataloguePage(page: Int): Result<MovieLinkPage> {
        val safePage = page.coerceIn(1, MovieLinkParser.MAX_PAGE)
        val html = get("$PAGE_PATH/$safePage").getOrElse { return Result.failure(it) }

        val cards = MovieLinkParser.cards(html)
        val hasNext = MovieLinkParser.hasNextPage(html, safePage)
        log("page $safePage: ${cards.size} cards, hasNext=$hasNext")
        return Result.success(MovieLinkPage(safePage, cards, hasNext))
    }

    /** Interface-adjacent listing that swallows the typed failure. */
    suspend fun listing(page: Int): List<ProviderHit> {
        val result = cataloguePage(page).getOrElse { error ->
            log("listing page $page failed: ${error.message}")
            return emptyList()
        }
        return hitsOf(result.cards)
    }

    /** One detail page, parsed. The typed entry point [episodes] is built on. */
    suspend fun detail(seriesId: String): Result<MovieLinkDetail> {
        val target = MovieLinkTarget.parse(seriesId)
            ?: return Result.failure(MovieLinkException("not a $PROVIDER_ID page id: $seriesId"))

        val html = get("/" + target.seriesId).getOrElse { return Result.failure(it) }
        return Result.success(MovieLinkParser.detail(html, target.seriesId, target.kind))
    }

    // ------------------------------------------------------------------ mapping

    private fun hitsOf(cards: List<MovieLinkCard>): List<ProviderHit> = cards.map { card ->
        ProviderHit(
            providerId = PROVIDER_ID,
            seriesId = card.seriesId,
            title = card.title,
            // Cards print a root-relative `src`; the catalogue needs an absolute URL to render.
            poster = absolute(card.poster),
            year = card.year,
        )
    }

    private fun entriesOf(detail: MovieLinkDetail): List<ProviderEpisode> {
        if (detail.episodes.isEmpty()) {
            val player = absolute(detail.playerUrl)
            if (player.isEmpty()) return emptyList()
            return listOf(
                ProviderEpisode(
                    providerId = PROVIDER_ID,
                    seriesId = detail.seriesId,
                    number = 1,
                    title = movieLabel(detail),
                    subUrl = player,
                    dubUrl = null,
                    remoteId = tokenOf(player),
                ),
            )
        }

        // A season grid resets its numbering at 1, and `ProviderEpisode.number` is the only ordering
        // key the model has: renumbering positionally keeps the list monotonic, and the true
        // "Season 2 · Episode 1" stays readable in the title.
        val multipleSeasons = detail.episodes.mapNotNull { it.season }.distinct().size > 1
        val byNumber = LinkedHashMap<Int, ProviderEpisode>(detail.episodes.size)

        detail.episodes.forEachIndexed { index, row ->
            val link = absolute(row.link)
            if (link.isEmpty()) return@forEachIndexed
            val number = if (multipleSeasons) index + 1 else row.number
            val episode = ProviderEpisode(
                providerId = PROVIDER_ID,
                seriesId = detail.seriesId,
                number = number,
                title = episodeLabel(row, withSeason = multipleSeasons),
                subUrl = link,
                dubUrl = null,
                remoteId = row.token,
            )
            if (byNumber.containsKey(number)) {
                log("${detail.seriesId}: duplicate episode number $number dropped")
            } else {
                byNumber[number] = episode
            }
        }

        return byNumber.values.toList()
    }

    /** For a film the "episode title" is the published quality, which is what distinguishes copies. */
    private fun movieLabel(detail: MovieLinkDetail): String =
        detail.versions.joinToString(" / ")
            .ifEmpty { detail.resolution.trim() }
            .ifEmpty { detail.quality.trim() }

    private fun episodeLabel(row: MovieLinkEpisode, withSeason: Boolean): String {
        val parts = buildList {
            if (withSeason) {
                row.season?.let { add("S$it") }
                add("Ep ${row.number}")
            }
            row.quality.takeIf { it.isNotEmpty() }?.let(::add)
        }
        // The raw button label is the fallback so a page without quality badges still shows "Ep 12".
        return parts.joinToString(" · ").ifEmpty { row.label }
    }

    private fun tokenOf(url: String): String? =
        PLAYER_TOKEN.find(url)?.groupValues?.getOrNull(1)?.takeIf { TOKEN.matches(it) }

    // ------------------------------------------------------------------ transport

    private suspend fun get(path: String): Result<String> {
        if (base.isEmpty()) return Result.failure(MovieLinkException("index host is not configured"))
        val url = (base + path).toHttpUrlOrNull()
            ?: return Result.failure(MovieLinkException("unusable path '$path'"))
        return fetch(url)
    }

    /**
     * One GET with pacing, bounded retries and a Cloudflare kill switch.
     *
     * 403 and any challenge body are terminal: the measured failure mode is "this request did not
     * look like a browser", which a retry reproduces exactly. 408/425/429/5xx are retried with
     * exponential backoff plus jitter so several devices cannot synchronise their retries.
     */
    private suspend fun fetch(url: HttpUrl): Result<String> {
        var attempt = 0
        var last: MovieLinkException? = null

        while (attempt < MAX_ATTEMPTS) {
            pace()

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Referer", "$base/")
                .build()

            val outcome = try {
                client.newCall(request).awaitResponse().use { response ->
                    Outcome(
                        code = response.code,
                        body = response.body?.string().orEmpty(),
                        mitigated = response.header(HEADER_MITIGATED),
                    )
                }
            } catch (error: IOException) {
                // A cancelled call surfaces as an IOException; cancellation must not become a retry.
                currentCoroutineContext().ensureActive()
                Outcome(code = 0, body = "", mitigated = null, error = error)
            }

            if (looksChallenged(outcome)) {
                log("challenge on $url (HTTP ${outcome.code})")
                return Result.failure(MovieLinkBlockedException(outcome.code, "challenge on $url"))
            }

            val error = outcome.error
            if (error != null) {
                last = MovieLinkException("GET $url failed: ${error.message}", error)
                if (attempt < MAX_ATTEMPTS - 1) {
                    val wait = backoffMs(attempt)
                    log("io error on $url -> retry in ${wait}ms (${attempt + 1}/$MAX_ATTEMPTS)")
                    delay(wait)
                }
                attempt += 1
                continue
            }

            if (outcome.code in 200..299) return Result.success(bound(outcome.body))

            if (outcome.code == HTTP_FORBIDDEN) {
                log("403 on $url — the request did not look like a browser")
                return Result.failure(MovieLinkBlockedException(outcome.code, "HTTP 403 on $url"))
            }

            if (outcome.code in RETRYABLE_CODES && attempt < MAX_ATTEMPTS - 1) {
                last = MovieLinkHttpException(outcome.code, "HTTP ${outcome.code} on $url")
                val wait = backoffMs(attempt)
                log("HTTP ${outcome.code} on $url -> retry in ${wait}ms (${attempt + 1}/$MAX_ATTEMPTS)")
                delay(wait)
                attempt += 1
                continue
            }

            return Result.failure(MovieLinkHttpException(outcome.code, "HTTP ${outcome.code} on $url"))
        }

        return Result.failure(last ?: MovieLinkException("GET $url failed after $MAX_ATTEMPTS attempts"))
    }

    /**
     * Serial spacing between request starts. Holding the mutex across the delay is what makes the
     * spacing real: concurrent callers queue behind it instead of all passing the check.
     */
    private suspend fun pace() {
        if (minIntervalMs <= 0L) return
        paceLock.withLock {
            val waitNanos = lastStartedAtNanos + minIntervalMs * NANOS_PER_MILLI - System.nanoTime()
            if (waitNanos > 0L) delay(waitNanos / NANOS_PER_MILLI + 1L)
            lastStartedAtNanos = System.nanoTime()
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = BASE_BACKOFF_MS shl attempt.coerceIn(0, 4)
        val jitter = kotlin.random.Random.nextLong(0L, BACKOFF_JITTER_MS)
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun looksChallenged(outcome: Outcome): Boolean {
        if (outcome.mitigated?.contains("challenge", ignoreCase = true) == true) return true
        if (outcome.body.isEmpty()) return false
        return CHALLENGE_MARKERS.any { outcome.body.contains(it) }
    }

    private fun bound(body: String): String =
        if (body.length <= MAX_DOCUMENT_CHARS) body else body.substring(0, MAX_DOCUMENT_CHARS)

    /**
     * Resolves a page-relative path against the current mirror.
     *
     * A `//host/…` protocol-relative URL keeps the scheme of the origin; anything else that is not
     * absolute is a path on this origin. A blank base (unconfigured provider) yields `""` so the
     * caller can skip the row instead of emitting a broken link.
     */
    private fun absolute(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        if (base.isEmpty()) return ""
        if (value.startsWith("//")) return "https:$value"
        return if (value.startsWith("/")) base + value else "$base/$value"
    }

    private data class Outcome(
        val code: Int,
        val body: String,
        val mitigated: String?,
        val error: IOException? = null,
    )

    /**
     * One detail-page id: `"movie/<slug>"`. A bare slug is rejected — the kind is the route, and
     * guessing it would mean up to four 404s per lookup.
     */
    private data class MovieLinkTarget(val seriesId: String, val kind: MovieLinkKind) {
        companion object {
            fun parse(raw: String): MovieLinkTarget? {
                val value = raw.trim().trim('/')
                if (value.isEmpty() || value.length > MAX_ID_CHARS) return null

                // Accept an absolute page URL too: a caller that stored a link before the mirror
                // rotation still gets a usable id, and the host part is simply discarded.
                val path = if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                    value.substringAfter("://", "").substringAfter('/', "").trim('/')
                } else {
                    value
                }

                val kind = MovieLinkKind.ofPath(path.substringBefore('/')) ?: return null
                val slug = path.substringAfter('/', "").substringBefore('?').substringBefore('#')
                if (!SLUG.matches(slug)) return null
                return MovieLinkTarget("${kind.path}/$slug", kind)
            }
        }
    }

    private companion object {
        const val PROVIDER_ID = "movielink"

        const val SEARCH_PATH = "search"
        const val PAGE_PATH = "/page"

        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.9,bn;q=0.8"

        const val HTTP_FORBIDDEN = 403
        const val MAX_ATTEMPTS = 3
        const val MAX_DOCUMENT_CHARS = 4_000_000
        const val MAX_ID_CHARS = 400

        /** Measured: the listing answers a browser UA politely at ~1 req/s; do not go faster. */
        const val MIN_INTERVAL_MS = 1_000L

        const val BASE_BACKOFF_MS = 1_000L
        const val BACKOFF_JITTER_MS = 750L
        const val MAX_BACKOFF_MS = 20_000L

        const val NANOS_PER_MILLI = 1_000_000L

        const val HEADER_MITIGATED = "cf-mitigated"

        val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

        /** Shapes Cloudflare serves instead of the page; a retry against any of them is pointless. */
        val CHALLENGE_MARKERS = listOf(
            "Just a moment...",
            "Attention Required!",
            "__cf_chl",
            "cf-challenge",
            "challenge-platform/scripts/jsd",
        )

        val SLUG = Regex("[A-Za-z0-9][A-Za-z0-9._~%+-]{0,240}")
        val PLAYER_TOKEN = Regex("/p/([A-Za-z0-9_-]{2,128})")
        val TOKEN = Regex("[A-Za-z0-9_-]{2,128}")
    }
}

// ---------------------------------------------------------------------- errors

/** Transport or parse failure on the index. Extends [IOException] like every other provider here. */
open class MovieLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The index refused to answer a browser-shaped request: a Cloudflare challenge or a 403. The sync
 * layer must abort the run and back off — retrying is how a soft block becomes an IP ban.
 */
class MovieLinkBlockedException(val status: Int, message: String) : MovieLinkException(message)

/** A terminal non-2xx answer that is not a block (404 on a moved title, 410 on a pulled one). */
class MovieLinkHttpException(val status: Int, message: String) : MovieLinkException(message)

// ---------------------------------------------------------------------- transport

/**
 * Cancellation-aware bridge from OkHttp's callback API: a scraped page is up to ~600 KB, so a
 * coroutine must not pin a thread for the body read.
 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
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
