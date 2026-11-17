package com.animo.app.data.hindi

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.ServerOption
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.player.ResolveException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Playback for the Hindi-dub catalogue: an `episode.subUrl` page becomes an
 * `argon.razorshell.space` embed, the embed's `_juicycodes(...)` call is decoded, and the resulting
 * master playlist is handed to Media3.
 *
 * ## Why this class exists separately from [com.animo.app.data.repo.StreamRepositoryImpl]
 *
 * The original catalogue resolves through megaplay (`getSources` + AES + referer allowlist). The Hindi
 * hosts (toono.app, rareanimes.foo) both funnel into **argon**, which is a different protocol: a
 * browser-rendered JW Player whose playlist is hidden in a JS obfuscation rather than a JSON API. So
 * this is a peer of `StreamRepositoryImpl`, not a reuse of it — and the routing decision lives in
 * [com.animo.app.data.repo.RoutingStreamRepository] (the sign of the anime id).
 *
 * ## Measured chain (2026-11-17)
 *
 * 1. `animeId` picks the provider — see [Origin] and the ranges on `Anime.isHindi`. Rows are seeded
 *    with an `episode.subUrl` that means different things per origin:
 *    - **toono** — the episode page (`https://toono.app/episode/<slug>/`), resolved by
 *      [ToonoProvider.embedId] through the page's `#main-player` iframe and the `trembed` hop;
 *    - **rareanimes** — one of its player links (already an `argon …/embed/<id>`, a `/downlead/<id>`,
 *      a `multiquality` wrapper or a legacy `zipper` gate), turned into the embed URL by
 *      [RareAnimesProvider.embedUrl].
 * 2. `GET https://argon.razorshell.space/embed/<id>` with the same browser headers the providers use.
 *    The page body carries no playlist; it calls `_juicycodes(...)` with the obfuscated payload.
 * 3. [JuicyDecoder.extractPayload] concatenates the call's string arguments, [JuicyDecoder.decode]
 *    reverses the four-step transform and returns the `sources.file` inside the reconstructed
 *    `jwplayer().setup({...})`.
 *
 * The decoded value is used **verbatim**. It is a single-variant master (`#EXTM3U` listing three
 * variants, verified against the host's own `player.js`), so there is nothing to rewrite and no
 * `master.m3u8` path to synthesise — a "cleaner" URL here would only 404.
 *
 * ## Audio
 *
 * A Hindi stream is the dub by definition, so the returned [PlayableStream.audio] is always
 * [AudioTrack.DUB] whatever the caller asks for. The parameter still participates in the cache key
 * (mirroring [com.animo.app.data.repo.StreamRepositoryImpl]) so the two tracks never share an entry,
 * but it cannot change the bytes that are returned.
 *
 * ## Cache
 *
 * Identical policy to `StreamRepositoryImpl` on purpose, so the two backends cannot drift: 15 minutes,
 * at most 24 entries, LRU eviction, keyed by `(episodeId, audio)` **plus** the input URL. The URL is
 * part of the identity because the providers re-host episodes: when an episode row is re-synced with a
 * new `subUrl`, the argon id behind it usually changes too and the cached manifest is dead. A cached
 * hit is re-checked under the mutex, so a burst of taps on one episode issues exactly one resolution.
 *
 * ## Failure contract
 *
 * `resolve` never throws. Cancellation is the single exception: it is rethrown rather than turned
 * into a `Result.failure`, because a cached failure would be worse than the cancellation itself.
 * Everything else is mapped onto [ResolveException] — see [classify] — so the player and the error
 * sheet can keep treating Hindi failures like megaplay ones.
 *
 * Plain JVM (`okhttp3`, coroutines, no `android.*`) so it is unit-testable without Robolectric.
 *
 * @param http client used only for the argon embed GET. The providers own their own hops.
 * @param ttlMs cache lifetime; injectable so tests do not have to sleep 15 minutes.
 * @param clock wall clock for the TTL, `System.currentTimeMillis()` here because
 *   `android.os.SystemClock` would drag `android.*` into a JVM-testable class.
 * @param dispatcher dispatcher for the blocking OkHttp `execute()` on the embed GET.
 * @param log one-line trace sink. Defaults to a no-op: [com.animo.app.AppLog] imports
 *   `android.util.Log`, so production wiring passes `AppLog::d` and this file stays android-free.
 */
class HindiStreamRepositoryImpl(
    private val http: OkHttpClient,
    private val toono: ToonoProvider,
    private val rareAnimes: RareAnimesProvider,
    private val ttlMs: Long = CACHE_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamRepository {

    private enum class Origin { SOURCE_A, SOURCE_B }

    private data class Key(val episodeId: Long, val audio: AudioTrack)

    private class Entry(
        val stream: PlayableStream,
        val sourceUrl: String,
        val storedAt: Long,
    )

    private class Page(val code: Int, val body: String)

    private val mutex = Mutex()

    private val lock = Any()

    /** Access-ordered so the eviction policy is LRU rather than arbitrary. */
    private val cache = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, 0.75f, true)

    /** Hops of the most recent resolution, for [describe]. */
    @Volatile
    private var trace: List<String> = emptyList()

    // ------------------------------------------------------------------ StreamRepository

    /**
     * A Hindi row has exactly one server: the catalogue it came from. Its audio is Hindi by
     * definition, so there is no language choice to offer here.
     */
    override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> = listOf(
        ServerOption(
            server = StreamServer.PRIMARY,
            languages = listOf(
                ServerLanguage(id = "hi", label = "Hindi (DUB)", audio = AudioTrack.DUB, languageCode = "hi"),
            ),
            probed = true,
            reachable = true,
        ),
    )

    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> {
        val origin = originOf(animeId)
            ?: return Result.failure(
                // Not a ResolveException on purpose: a positive or out-of-range id reaching this
                // class is a routing bug, and RoutingStreamRepository only sends negative ids here.
                IllegalArgumentException(
                    "not a Hindi id: $animeId (expected $RARE_MIN_ID..-1)",
                ),
            )

        val source = episode.subUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(
                ResolveException.NotFound("episode ${episode.id} has no Hindi source url"),
            )

        val key = Key(episode.id, audio)
        read(key, source)?.let {
            log("hindi cache hit ep${episode.id} ${audio.name}")
            return Result.success(it)
        }

        return mutex.withLock {
            // Second check: another caller may have resolved while we queued for the lock.
            read(key, source)?.let {
                log("hindi cache hit after lock ep${episode.id} ${audio.name}")
                return@withLock Result.success(it)
            }

            resolveUncached(origin, source, key).also { result ->
                result.onSuccess { stream -> store(key, Entry(stream, source, clock())) }
                result.onFailure { error ->
                    log("hindi resolve failed ep${episode.id} ${origin.name}: ${error.message}")
                }
            }
        }
    }

    override suspend fun invalidate(episodeId: Long) {
        synchronized(lock) {
            cache.keys.removeAll { key -> key.episodeId == episodeId }
        }
        log("hindi invalidated cached streams for ep$episodeId")
    }

    /** Drops everything (e.g. on a hard network change, or when a provider starts answering 403). */
    fun invalidateAll() {
        synchronized(lock) { cache.clear() }
        log("hindi invalidated every cached stream")
    }

    /**
     * The hop trail of the most recent resolution, newest last: which provider saw the URL, which
     * argon id it produced, the embed's HTTP status and the decoded master's host. Intended for the
     * debug screen and bug reports — it holds no signed segment URLs.
     */
    fun describe(): String = trace.takeIf { it.isNotEmpty() }?.joinToString(separator = " | ")
        ?: "no Hindi resolution has run yet"

    /** The same trail as a list, capped to the last [LAST_HOPS] entries. */
    fun resolveTrace(): List<String> = trace.takeLast(LAST_HOPS)

    // ------------------------------------------------------------------ resolution

    private suspend fun resolveUncached(
        origin: Origin,
        source: String,
        key: Key,
    ): Result<PlayableStream> {
        val hops = mutableListOf("ep${key.episodeId} ${origin.name.lowercase()}")

        return try {
            val embedUrl = embedUrlFor(origin, source, hops)
            val html = withContext(dispatcher) { fetchEmbed(embedUrl, hops) }

            val payload = JuicyDecoder.extractPayload(html)
                ?: throw ResolveException.Parse(
                    "argon embed ${embedUrl.substringAfterLast('/')} carries no _juicycodes call",
                )
            val masterUrl = try {
                JuicyDecoder.decode(payload)
            } catch (error: IllegalArgumentException) {
                // The host changed its scheme: the decoder's precondition failures are all this.
                throw ResolveException.Parse("juicy decode: ${error.message}")
            }
            hops += "master ${hostOf(masterUrl)} ${masterUrl.length}b"

            Result.success(
                PlayableStream(
                    masterUrl = masterUrl,
                    referer = ARGON_REFERER,
                    userAgent = USER_AGENT,
                    audio = AudioTrack.DUB,
                    thumbnails = null,
                    intro = null,
                    outro = null,
                ),
            )
        } catch (error: ResolveException) {
            hops += "failed ${error.javaClass.simpleName}: ${error.message}"
            Result.failure(error)
        } catch (error: CancellationException) {
            // A cancelled resolve must not become a cached-looking failure.
            hops += "cancelled"
            throw error
        } catch (error: Exception) {
            hops += "failed ${error.javaClass.simpleName}: ${error.message}"
            Result.failure(classify(error, origin))
        } finally {
            trace = hops.toList()
            log("hindi ${hops.joinToString(separator = " | ")}")
        }
    }

    /** Provider hop: episode page / player link -> the argon embed URL. */
    private suspend fun embedUrlFor(
        origin: Origin,
        source: String,
        hops: MutableList<String>,
    ): String = when (origin) {
        Origin.SOURCE_A -> {
            val videoId = toono.embedId(source)
            val embedUrl = ARGON_EMBED_PREFIX + videoId
            // The provider's own regex already constrains the id; this rejects a host change that
            // would otherwise turn into a confusing OkHttp "unexpected url" exception.
            if (embedUrl.toHttpUrlOrNull() == null) {
                throw ResolveException.Parse("source A returned an unusable video id '$videoId'")
            }
            hops += "source A id $videoId"
            embedUrl
        }

        Origin.SOURCE_B -> {
            val embedUrl = rareAnimes.embedUrl(source)
            hops += "ra embed ${embedUrl.substringAfterLast('/')}"
            rareAnimes.resolveTrace()
                .takeIf { it.isNotEmpty() }
                ?.let { hops += "ra hops ${it.joinToString(separator = " ; ")}" }
            embedUrl
        }
    }

    /**
     * One embed page.
     *
     * A 403 is a definite "not for this UA/referer" and a 404/410 means the episode was pulled, so both
     * are fatal. 408/425/429/5xx and IO errors are retried once with a short pause: the argon host sits
     * behind a CDN that emits transient 5xx under load, and a tap-to-play must not surface that.
     */
    private suspend fun fetchEmbed(embedUrl: String, hops: MutableList<String>): String {
        var last: ResolveException = ResolveException.Network(IOException("argon embed: no attempt"))

        for (attempt in 1..EMBED_ATTEMPTS) {
            if (attempt > 1) delay(RETRY_DELAY_MS * (attempt - 1))

            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT_HTML)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Referer", ARGON_REFERER)
                .get()
                .build()

            val page = try {
                http.newCall(request).execute().use { response ->
                    Page(response.code, response.body?.string().orEmpty())
                }
            } catch (error: IOException) {
                hops += "embed #$attempt io ${error.message}"
                last = ResolveException.Network(error)
                continue
            }

            hops += "embed #$attempt ${page.code} ${page.body.length}b"
            when {
                page.code in 200..299 && page.body.isNotBlank() -> return page.body
                page.code == 403 -> throw ResolveException.Blocked("argon embed 403")
                page.code == 404 || page.code == 410 ->
                    throw ResolveException.NotFound("argon embed HTTP ${page.code}")
                page.code in 200..299 -> last = ResolveException.Parse("argon embed page is empty")
                page.code in RETRYABLE_CODES ->
                    last = ResolveException.Network(IOException("argon embed HTTP ${page.code}"))
                else -> throw ResolveException.Network(IOException("argon embed HTTP ${page.code}"))
            }
        }

        throw last
    }

    // ------------------------------------------------------------------ failure mapping

    /**
     * Provider errors arrive as plain [IOException]s carrying a message (see [ToonoException] /
     * [RareAnimesException]), so the message is the only signal available for "gone" vs "blocked" vs
     * "the page changed shape". Order matters: the subtypes are checked before the base type.
     */
    private fun classify(error: Throwable, origin: Origin): ResolveException = when (error) {
        is ResolveException -> error

        // A file host / batch archive: there is no embed, so there will never be a stream.
        is RareEmbedUnavailableException ->
            ResolveException.NotFound("${error.host}: ${error.message}")

        is RareAnimesBlockedException ->
            ResolveException.Blocked("${origin.name.lowercase()} blocked (${error.status}): ${error.message}")

        is IOException -> classifyIo(error)

        is IllegalArgumentException ->
            ResolveException.Parse("${error.javaClass.simpleName}: ${error.message}")

        else -> ResolveException.Parse("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }

    private fun classifyIo(error: IOException): ResolveException {
        val message = error.message.orEmpty()
        val status = HTTP_STATUS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            status == 404 || status == 410 -> ResolveException.NotFound(message)
            status == 403 || status == 429 -> ResolveException.Blocked(message)
            PARSE_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) } ->
                ResolveException.Parse(message)
            else -> ResolveException.Network(error)
        }
    }

    // ------------------------------------------------------------------ cache

    private fun read(key: Key, sourceUrl: String): PlayableStream? = synchronized(lock) {
        val entry = cache[key] ?: return@synchronized null
        val expired = clock() - entry.storedAt > ttlMs
        val sourceChanged = entry.sourceUrl != sourceUrl
        if (expired || sourceChanged) {
            cache.remove(key)
            null
        } else {
            entry.stream
        }
    }

    private fun store(key: Key, entry: Entry) {
        synchronized(lock) {
            cache[key] = entry
            while (cache.size > MAX_ENTRIES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun originOf(animeId: Int): Origin? = when (animeId) {
        in TOONO_MAX_ID until 0 -> Origin.SOURCE_A
        in RARE_MIN_ID..RARE_MAX_ID -> Origin.SOURCE_B
        else -> null
    }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url.take(48)

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val MAX_ENTRIES = 24
        const val INITIAL_CAPACITY = 16
        const val LAST_HOPS = 12

        /** -1 … -999,999,999 is toono.app, -1,000,000,000 … -1,999,999,999 is rareanimes.foo. */
        const val TOONO_MAX_ID = -999_999_999
        const val RARE_MAX_ID = -1_000_000_000
        const val RARE_MIN_ID = -1_999_999_999

        val ARGON_BASE: String by lazy { ProviderVault.url(ProviderKey.DUB_CDN) }
        val ARGON_EMBED_PREFIX: String by lazy { "$ARGON_BASE/embed/" }

        /** The only referer the argon player accepts for playback (verified on the decoded manifest). */
        val ARGON_REFERER: String by lazy { "$ARGON_BASE/" }

        /** Same UA the toono/rareanimes hops use; the embed page serves the payload to it. */
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "HeadlessChrome/156.0.0.0 Safari/537.36"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.6"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        const val EMBED_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 400L

        val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

        val HTTP_STATUS = Regex("HTTP (\\d{3})")

        /** Provider messages that mean "the page no longer has the shape this parser expects". */
        val PARSE_MARKERS = listOf(
            "no player frame",
            "no /embed",
            "no #playerIframe",
            "unparseable",
            "not an absolute",
            "implausible video id",
            "no data-href",
            "exceeded",
            "blank",
        )
    }
}
