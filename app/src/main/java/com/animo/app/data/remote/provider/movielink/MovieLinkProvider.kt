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
import com.animo.app.player.movielink.MovieLinkPd2
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
    /** How long a refreshed `/p/<token>` is reused before the title page is read again. */
    private val playerTokenTtlMs: Long = PLAYER_TOKEN_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AnimeProvider, MovieLinkCatalogue {

    override val id: String = PROVIDER_ID

    /**
     * Parsed once: every URL is built on top of it, and a host that is not a valid absolute URL is a
     * provisioning error that must degrade to "provider unavailable", not crash at start-up.
     */
    private val origin: HttpUrl? = indexHost.trim().trimEnd('/').toHttpUrlOrNull()

    private val base: String = origin?.toString()?.trimEnd('/').orEmpty()

    /** False when the vault slot was empty or malformed. The UI can hide the provider instead of failing. */
    override val isConfigured: Boolean get() = origin != null

    private val paceLock = Mutex()

    /** Serialises token refreshes so two simultaneous taps on one title cost one page fetch. */
    private val playerLinkLock = Mutex()

    /**
     * Title page id -> the `/p/<token>` last read from it. Absolute, so a mirror rotation between the
     * refresh and the play cannot produce a host that no longer answers.
     */
    private val playerTokens = java.util.concurrent.ConcurrentHashMap<String, PlayerToken>()

    private data class PlayerToken(val link: String, val atMs: Long)

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
     * Episode rows — which for this index means one of three shapes:
     * - **film**: the single `/p/<token>` of the page, numbered 1 and titled with the quality label;
     * - **series/drama/anime with a rendered grid**: one row per button of the
     *   `#mlbdEpisodeDownloads` grid (the older layout);
     * - **series/drama/anime where the grid carries only download links**: one row per `episodes[]`
     *   entry of the `/p/<token>` shell, which is where the playable stream of every episode lives.
     *
     * The third shape is the one this index uses today: the visible cards hold `data-dl` download
     * payloads and no player token at all, so the grid parse returns nothing and the whole series
     * used to collapse into a single film-shaped row titled by its quality.
     *
     * A failure is reported as an empty list (the interface has no Result), with the reason logged;
     * [detail] is the typed entry point for a caller that needs to tell "blocked" from "no episodes".
     */
    override suspend fun episodes(seriesId: String): List<ProviderEpisode> {
        val detail = detail(seriesId).getOrElse { error ->
            log("episodes($seriesId) failed: ${error.message}")
            return emptyList()
        }

        val grid = entriesOf(detail)
        if (grid.isNotEmpty()) return grid

        val shell = playerEntries(detail)
        if (shell.isNotEmpty()) return shell

        log("$seriesId published no playable entry")
        return emptyList()
    }

    /**
     * Expands the `/p/<token>` shell, which for a series lists **every** episode under one token.
     *
     * Numbering is positional (1…N), not the provider's own: the episode primary key is
     * `-(|animeId|*1000 + number)` with the number clamped to 999, so an absolute `1221` would
     * collide with every other row above 999. The real number stays readable in the title, and
     * [?ep=] — which is what the resolver reads — is the 1-based index this list is built in.
     */
    private suspend fun playerEntries(detail: MovieLinkDetail): List<ProviderEpisode> {
        val path = detail.playerUrl.trim()
        if (path.isEmpty()) return emptyList()

        val shell = get(relativePathOf(path)).getOrElse { error ->
            log("${detail.seriesId}: player shell failed: ${error.message}")
            return emptyList()
        }
        val payload = MovieLinkPd2.extract(shell) ?: return filmEntry(detail, path)
        // A film's shell holds one entry; nothing to expand.
        if (payload.episodes.size <= 1) return filmEntry(detail, path)

        val base = absolute(path)
        return payload.episodes.mapIndexed { index, source ->
            ProviderEpisode(
                providerId = PROVIDER_ID,
                seriesId = detail.seriesId,
                number = index + 1,
                title = playerEntryTitle(source),
                subUrl = withEpisodeParameter(base, index + 1),
                dubUrl = null,
                remoteId = source.number?.toString(),
            )
        }
    }

    /** The pre-existing single-row shape, kept for films and for a shell that cannot be parsed. */
    private fun filmEntry(detail: MovieLinkDetail, playerPath: String): List<ProviderEpisode> {
        val player = absolute(playerPath)
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

    /**
     * `Episode 1263` for a single entry, `Episode 1263–1268` for a batch one — a pack is one file,
     * and hiding its range would make the numbering look broken.
     */
    private fun playerEntryTitle(source: MovieLinkPd2.Source): String {
        val start = source.number
        val end = source.numEnd
        // Built from the numbers, not by editing the label: the label of a batch entry is already
        // "Episode 1227-1228", and replacing inside it produced "Episode 1227–1228-1228".
        if (start != null && end != null && end > start) return "Episode $start–$end"
        return source.label?.trim().orEmpty().ifEmpty { start?.let { "Episode $it" }.orEmpty() }
    }

    /** `?ep=N`, or `&ep=N` when the shell URL already has a query. */
    private fun withEpisodeParameter(url: String, index: Int): String =
        if (url.contains('?')) "$url&ep=$index" else "$url?ep=$index"

    /** The shell link is host-relative; [get] prepends the base, so strip an absolute prefix. */
    private fun relativePathOf(path: String): String {
        if (!path.startsWith("http://", true) && !path.startsWith("https://", true)) return path
        val afterScheme = path.substringAfter("://")
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash)
    }

    override suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack): PlaybackSource {
        val stored = episode.subUrl?.trim().orEmpty()
        if (stored.isEmpty()) {
            throw ProviderResolveException("no player link for ${episode.seriesId}#${episode.number}")
        }

        // A stored `/p/<token>` is a token, not an id, and it rotates — but it is usually still good,
        // so it is tried first and only a 400 pays for a fresh one.
        val stream = resolveWith(resolverProvider(), stored, episode).getOrElse { error ->
            throw ProviderResolveException("resolver: ${error.message}", error)
        }

        if (audio == AudioTrack.DUB) {
            log("${episode.seriesId}#${episode.number} has no dub; serving the original track")
        }

        return PlaybackSource(
            masterUrl = stream.masterUrl,
            // The `/p/` player expects the catalogue as its referer; the resolver may know better.
            referer = stream.referer.ifBlank { stored },
            userAgent = stream.userAgent.ifBlank { userAgent },
            audio = AudioTrack.SUB,
            isHls = stream.container == StreamContainer.HLS,
        )
    }

    /**
     * One resolve, plus the single self-heal a rotating token needs: if the shell answers
     * [com.animo.app.player.ResolveException.Stale], drop the cached token, read a new one from the
     * title page and try exactly once more.
     *
     * No loop: a second 400 means the page now serves a token that the shell still rejects, which is
     * a provider change, not a race — retrying it would only multiply requests.
     */
    private suspend fun resolveWith(
        resolver: StreamResolver,
        link: String,
        episode: ProviderEpisode,
    ): Result<com.animo.app.domain.model.PlayableStream> {
        val first = resolver.resolve(link, AudioTrack.SUB)
        if (first.exceptionOrNull() !is com.animo.app.player.ResolveException.Stale) return first

        log("${episode.seriesId}#${episode.number}: stale player token, refreshing")
        invalidatePlayerLink(episode.seriesId)
        val refreshed = playerLink(episode.seriesId)?.let { withEpisode(it, episode.number.coerceAtLeast(1)) }
            ?: return first
        if (refreshed == link) return first
        return resolver.resolve(refreshed, AudioTrack.SUB)
    }

    /** Applies an episode index to a refreshed link without losing an existing query. */
    private fun withEpisode(link: String, index: Int): String {
        val url = link.toHttpUrlOrNull() ?: return link
        if (url.queryParameter(EPISODE_PARAM) != null) return link
        return url.newBuilder().addQueryParameter(EPISODE_PARAM, index.toString()).build().toString()
    }

    // ------------------------------------------------------------------ catalogue

    /**
     * One listing page: `GET {host}/page/{N}`.
     *
     * [MovieLinkParser.MAX_PAGE] is the measured end of the catalogue (529 pages × 30 cards). A page
     * past it is clamped rather than requested, so a sync that drifts cannot turn into a stream of
     * 404s.
     */
    override suspend fun cataloguePage(page: Int): Result<MovieLinkPage> {
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

    // ------------------------------------------------------------------ fresh player token

    /**
     * The title's **current** `/p/<token>`, absolute, or `null` when the page has no player.
     *
     * ## Why this exists
     * `/p/<token>` is not an id, it is a rotating token. Measured on 2026-11-26: a token captured on
     * 2026-11-20 answers `400 bad config`, while the token on the same title page right now answers
     * `200`. A synced row therefore carries a link that stops working, and the player used to show
     * that HTTP 400 as its error.
     *
     * ## Why it is cached
     * The title page is ~110 KB gzipped and its token is stable for minutes, so a binge of eight
     * episodes must not cost eight page fetches. [PLAYER_TOKEN_TTL_MS] is far below the measured
     * lifetime of a token and far above the gap between two episodes.
     *
     * @param seriesId `"series/<slug>"` — the same id the catalogue row stores.
     */
    suspend fun playerLink(seriesId: String): String? {
        val target = MovieLinkTarget.parse(seriesId) ?: return null
        val key = target.seriesId
        cachedPlayerLink(key)?.let { return it }

        // Two taps on the same title must not both fetch the page.
        return playerLinkLock.withLock {
            cachedPlayerLink(key)?.let { return@withLock it }

            // Unpaced on purpose: pacing protects the 529-page crawl, and a viewer waiting on a tap
            // is not a crawl. A failure falls back to a token we already had rather than to nothing.
            val html = get("/" + key, paced = false).getOrElse { error ->
                log("$key: player token refresh failed: ${error.message}")
                return@withLock playerTokens[key]?.link
            }
            val path = MovieLinkParser.playerPath(html)
            val link = absolute(path)
            if (link.isEmpty()) {
                log("$key: page published no player frame")
                return@withLock playerTokens[key]?.link
            }
            playerTokens[key] = PlayerToken(link, System.currentTimeMillis())
            log("$key: player token refreshed")
            link
        }
    }

    private fun cachedPlayerLink(key: String): String? {
        val entry = playerTokens[key] ?: return null
        if (System.currentTimeMillis() - entry.atMs > playerTokenTtlMs) return null
        return entry.link
    }

    /** Drops the cached token for one title (used when the origin rejects it as stale). */
    fun invalidatePlayerLink(seriesId: String) {
        MovieLinkTarget.parse(seriesId)?.let { playerTokens.remove(it.seriesId) }
    }

    // ------------------------------------------------------------------ mapping

    /** Absolute poster for a card `src`, so a synced row never depends on the current mirror. */
    override fun posterUrl(raw: String): String = absolute(raw)

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

    /**
     * Rows from the rendered grid, and nothing else. An empty result is the caller's signal to look
     * at the player shell instead: the current layout renders the cards with `data-dl` download
     * payloads only, so they produce no rows even though the series has dozens of episodes.
     */
    private fun entriesOf(detail: MovieLinkDetail): List<ProviderEpisode> {
        if (detail.episodes.isEmpty()) return emptyList()

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

    private suspend fun get(path: String, paced: Boolean = true): Result<String> {
        if (base.isEmpty()) return Result.failure(MovieLinkException("index host is not configured"))
        val url = (base + path).toHttpUrlOrNull()
            ?: return Result.failure(MovieLinkException("unusable path '$path'"))
        return fetch(url, paced)
    }

    /**
     * One GET with pacing, bounded retries and a Cloudflare kill switch.
     *
     * 403 and any challenge body are terminal: the measured failure mode is "this request did not
     * look like a browser", which a retry reproduces exactly. 408/425/429/5xx are retried with
     * exponential backoff plus jitter so several devices cannot synchronise their retries.
     */
    private suspend fun fetch(url: HttpUrl, paced: Boolean = true): Result<String> {
        var attempt = 0
        var last: MovieLinkException? = null

        while (attempt < MAX_ATTEMPTS) {
            if (paced) pace()

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

    /**
     * True only for an interstitial, never for a real page.
     *
     * The index started injecting Cloudflare's JS-detection bootstrap
     * (`/cdn-cgi/challenge-platform/scripts/jsd/main.js`) into **ordinary** title pages, and that
     * snippet contains one of [CHALLENGE_MARKERS]. Matching on the marker alone therefore declared
     * every 200 a block and took the whole catalogue offline; measured on 2026-11-26 a real title
     * page is ~535 KB while a Cloudflare block is a ~6 KB interstitial, so the size gate is what
     * distinguishes them. The header is still trusted outright — it is the origin saying so.
     */
    private fun looksChallenged(outcome: Outcome): Boolean {
        if (outcome.mitigated?.contains("challenge", ignoreCase = true) == true) return true
        if (outcome.body.isEmpty()) return false
        // A challenge never carries a whole catalogue page.
        if (outcome.body.length > MAX_CHALLENGE_CHARS) return false
        return CHALLENGE_MARKERS.any { outcome.body.contains(it, ignoreCase = true) }
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
        const val PROVIDER_ID = MovieLinkCatalogue.PROVIDER_ID

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

        /**
         * Largest body that can still be a Cloudflare interstitial. A real page is two orders of
         * magnitude past this, and a block page is well under it.
         */
        const val MAX_CHALLENGE_CHARS = 64_000

        /**
         * Reuse window for a refreshed `/p/<token>`.
         *
         * The token is stable for at least tens of minutes (measured 2026-11-26: three tokens read
         * ~20 minutes apart all still answered 200) while a token from six days earlier answers 400.
         * Ten minutes keeps a binge on one page fetch and stays far inside the observed lifetime.
         */
        const val PLAYER_TOKEN_TTL_MS = 10 * 60 * 1000L

        /** Query parameter the player resolver reads to pick one entry of a series shell. */
        const val EPISODE_PARAM = "ep"

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
