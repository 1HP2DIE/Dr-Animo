package com.animo.app.data.repo

import android.os.SystemClock
import com.animo.app.AppLog
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.ServerOption
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.AlternativeStreamSource
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import com.animo.app.domain.repo.FallbackStreamSource
import com.animo.app.domain.repo.SourceRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin cache in front of [StreamResolver].
 *
 * Resolution costs 3 upstream requests (embed page -> getSources -> decrypt), so a re-resolve caused
 * by a rotation or a re-buffer must not repeat it. Entries live 15 minutes and are keyed by
 * `(episodeId, audio)` **and** the embed URL that produced them: if an episode's embed URL changes
 * (server re-upload), the cached manifest is stale and must not be reused.
 *
 * The resolver itself is injected lazily through [resolverProvider] because the player module is
 * compiled separately (see ServiceLocator) — this class only ever sees the interface.
 *
 * ## Routing
 * This class is the **original-catalogue** backend, but the id space is what decides which backend
 * owns a request, so the dispatch lives here as well as in
 * [RoutingStreamRepository] — one rule, stated once:
 *
 * | id                              | backend                          |
 * |---------------------------------|----------------------------------|
 * | `>= 0`                          | this class (megapay + fallbacks)  |
 * | `-1 … -2_000_000_000`           | the Hindi delegate ([hindi])      |
 * | `-2_100_000_000 … -2_000_000_001`| the movielink delegate ([movieLink]) |
 * | anything else                   | a typed failure, never a guess    |
 *
 * Both delegates are nullable lambdas on purpose: a build without the Hindi or the movielink layer
 * answers a normal `Result.failure` instead of crashing, and this class stays usable in a unit test
 * with no container at all.
 */
class StreamRepositoryImpl(
    private val resolverProvider: () -> StreamResolver,
    private val ttlMs: Long = CACHE_TTL_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Second chance when the primary embed chain fails — see [AlternativeStreamSource]. */
    private val alternative: AlternativeStreamSource? = null,
    /** AniList id for the catalogue row, read lazily so this layer never touches Room directly. */
    private val anilistIdOf: suspend (animeId: Int) -> String? = { null },
    /** Titles and year of the row, used to pick the right season on the alternative server. */
    private val animeTitlesOf: suspend (animeId: Int) -> List<String> = { emptyList() },
    private val animeYearOf: suspend (animeId: Int) -> Int? = { null },
    /** Servers 3+, tried after the primary and the alternative, in this order. */
    private val fallbacks: List<FallbackStreamSource> = emptyList(),
    /** How long the server menu waits for any one server's answer. */
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    /**
     * The Hindi-dub backend. Read lazily because it is registered after the container is built; when
     * [RoutingStreamRepository] has already split on the sign, this is never consulted.
     */
    private val hindi: () -> StreamRepository? = { null },
    /** The movielinkbd backend (its own id space below Hindi). Same lazy-null contract as [hindi]. */
    private val movieLink: () -> StreamRepository? = { null },
) : StreamRepository {

    private data class Key(
        val episodeId: Long,
        val audio: AudioTrack,
        val server: StreamServer?,
        val languageId: String?,
    )

    private class Entry(
        val stream: PlayableStream,
        val storedAt: Long,
    )

    private val mutex = Mutex()

    private val lock = Any()

    /** Access-ordered so the eviction policy is LRU rather than arbitrary. */
    private val cache = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, 0.75f, true)

    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> = resolve(animeId, episode, audio, server = null, languageId = null)

    /**
     * Resolves on one server.
     *
     * `server = null` is the "auto" path that keeps a dead server invisible: the primary embed is
     * tried first, then the alternative. An explicit choice is respected — if the viewer picked
     * Server 2 it is not silently swapped for Server 1, but a *failure* still falls through to the
     * other server so playback can start.
     */
    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
        server: StreamServer?,
        languageId: String?,
    ): Result<PlayableStream> {
        // Three-way dispatch by id space. Ownership comes before any work: a movielink episode must not
        // be looked up in this catalogue's tables, and an id that belongs to neither is a routing bug.
        if (animeId < 0) {
            if (MovieLinkIds.isMovieLink(animeId)) {
                return movieLink()?.resolve(animeId, episode, audio, server, languageId)
                    ?: Result.failure(
                        IllegalStateException(
                            "MovieLink playback is not available in this build (id $animeId)",
                        ),
                    )
            }
            if (animeId >= HINDI_MIN_ID) {
                return hindi()?.resolve(animeId, episode, audio, server, languageId)
                    ?: Result.failure(
                        IllegalStateException(
                            "Hindi playback is not available in this build (id $animeId)",
                        ),
                    )
            }
            return Result.failure(
                // A ResolveException is deliberately not used: an id outside every range is a
                // routing bug, and the error sheet must not offer "try again" for it.
                IllegalArgumentException("no catalogue owns anime id $animeId"),
            )
        }

        val key = Key(episode.id, audio, server, languageId)
        read(key)?.let { return Result.success(it) }

        return mutex.withLock {
            read(key)?.let { return@withLock Result.success(it) }

            // Auto: numeric order. An explicit pick goes first; the rest still catch a failure.
            val all = listOf(StreamServer.PRIMARY, StreamServer.ALTERNATIVE) + fallbacks.map { it.server }
            val order = if (server == null || server !in all) all else listOf(server) + (all - server)
            var request: SourceRequest? = null

            var last: Result<PlayableStream>? = null
            for (candidate in order) {
                val attempt = when (candidate) {
                    StreamServer.PRIMARY -> {
                        // The primary only has the original and the English dub. Falling back to it
                        // for a language it does not carry (Hindi, Tamil, …) would silently switch
                        // the audio, which is worse than saying the episode is unavailable there.
                        val primaryAudio = primaryAudioFor(audio, languageId)
                        if (primaryAudio == null) {
                            Result.failure(
                                ResolveException.NotFound("that language is not on this server"),
                            )
                        } else {
                            resolvePrimary(episode, primaryAudio)
                        }
                    }
                    StreamServer.ALTERNATIVE -> resolveAlternative(animeId, episode, audio, languageId)
                    else -> {
                        val source = fallbacks.firstOrNull { it.server == candidate }
                        if (source == null) {
                            Result.failure(ResolveException.NotFound("no such server"))
                        } else {
                            val built = request ?: sourceRequest(animeId, episode).also { request = it }
                            source.resolve(built, audio, languageId)
                                .onFailure { AppLog.w("server ${candidate.number} failed for ep${episode.id}: ${it.message}") }
                        }
                    }
                }
                if (attempt.isSuccess) {
                    val stream = attempt.getOrThrow().copy(server = candidate)
                    store(key, Entry(stream, clock()))
                    AppLog.d("resolved ep${episode.id} $audio via $candidate/${languageId ?: "-"}")
                    return@withLock Result.success(stream)
                }
                last = attempt
            }

            last ?: Result.failure(ResolveException.NotFound("no server for this episode"))
        }
    }

    /**
     * Every server, with the languages it actually offers for this episode.
     *
     * All servers are asked at once and each answer is capped at [probeTimeoutMs], so one slow host
     * cannot hold the menu. A server that times out is shown as unreachable rather than missing.
     */
    override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> = coroutineScope {
        // A delegate owns its own server menu; asking this catalogue would invent servers for a title
        // that is not in it.
        when {
            MovieLinkIds.isMovieLink(animeId) -> return@coroutineScope movieLink()?.serverOptions(animeId, episode).orEmpty()
            animeId < 0 -> return@coroutineScope hindi()?.serverOptions(animeId, episode).orEmpty()
        }
        val request = async { sourceRequest(animeId, episode) }
        val primary = async { primaryOption(episode) }
        val alternativeOption = async { alternativeOption(animeId, episode) }
        val others = fallbacks.map { source ->
            async {
                val languages = withTimeoutOrNull(probeTimeoutMs) {
                    runCatching { source.languages(request.await()) }.getOrDefault(emptyList())
                }
                ServerOption(
                    server = source.server,
                    languages = languages.orEmpty(),
                    probed = true,
                    reachable = !languages.isNullOrEmpty(),
                )
            }
        }
        listOfNotNull(primary.await(), alternativeOption.await()) + others.awaitAll()
    }

    /**
     * The primary's languages, as the catalogue lists them — except that a listed dub is *checked*.
     *
     * Measured: 8 of 13 dub links the catalogue lists are dead embeds ("no player"). Showing those as
     * available sent viewers to a failure and then a silent fall-through. The check is a real
     * resolve, so a live dub is also cached and starts instantly when picked.
     */
    private suspend fun primaryOption(episode: Episode): ServerOption {
        val hasSub = !episode.subUrl.isNullOrBlank()
        val dubUrl = episode.dubUrl?.takeIf { it.isNotBlank() }
        val dubAlive = dubUrl != null && withTimeoutOrNull(probeTimeoutMs) {
            resolverProvider().resolve(dubUrl, AudioTrack.DUB).exceptionOrNull()
                .let { it !is ResolveException.NotFound && it !is ResolveException.Dead }
        } != false
        val languages = buildList {
            if (hasSub) add(ServerLanguage(id = "sub", label = "Original (SUB)", audio = AudioTrack.SUB))
            if (dubAlive) {
                // The catalogue's dub track is the English dub; Hindi dubs are their own
                // catalogue rows and reach the player through the Hindi repository.
                add(ServerLanguage(id = "dub", label = "English (DUB)", audio = AudioTrack.DUB, languageCode = "en"))
            }
        }
        return ServerOption(
            server = StreamServer.PRIMARY,
            languages = languages,
            probed = true,
            reachable = languages.isNotEmpty(),
        )
    }

    private suspend fun alternativeOption(animeId: Int, episode: Episode): ServerOption? {
        val alternative = alternative ?: return null
        val anilistId = runCatching { anilistIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return ServerOption(StreamServer.ALTERNATIVE, probed = true, reachable = false)

        val tracks = withTimeoutOrNull(probeTimeoutMs) {
            runCatching {
                alternative.languages(anilistId, episode, seasonHintOf(animeId), yearOf(animeId))
            }.getOrDefault(emptyList())
        }.orEmpty()
        return ServerOption(
            server = StreamServer.ALTERNATIVE,
            languages = tracks.map { track ->
                val code = track.language.ifBlank { "und" }
                ServerLanguage(
                    id = code,
                    label = when (code) {
                        "ja" -> "Original (Japanese)"
                        "hi" -> "Hindi"
                        "en" -> "English"
                        "ta" -> "Tamil"
                        "te" -> "Telugu"
                        "ml" -> "Malayalam"
                        "kn" -> "Kannada"
                        else -> track.label.ifBlank { code.uppercase() }
                    },
                    audio = if (code == "ja") AudioTrack.SUB else AudioTrack.DUB,
                    languageCode = code,
                )
            },
            probed = true,
            reachable = tracks.isNotEmpty(),
        )
    }

    private suspend fun sourceRequest(animeId: Int, episode: Episode): SourceRequest = SourceRequest(
        episode = episode,
        anilistId = runCatching { anilistIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() },
        titles = runCatching { animeTitlesOf(animeId) }.getOrDefault(emptyList()).filter { it.isNotBlank() },
        year = yearOf(animeId),
    )

    /**
     * Which primary track can satisfy a requested language: the original track speaks for `sub`/`ja`,
     * the English dub for `dub`/`en`. Anything else has no equivalent on that server.
     */
    private suspend fun seasonHintOf(animeId: Int): Int? =
        runCatching { com.animo.app.player.AltStreamProtocol.seasonFromTitle(animeTitlesOf(animeId)) }
            .getOrNull()

    private suspend fun yearOf(animeId: Int): Int? = runCatching { animeYearOf(animeId) }.getOrNull()

    private suspend fun resolvePrimary(
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> {
        val embedUrl = when (audio) {
            AudioTrack.SUB -> episode.subUrl
            AudioTrack.DUB -> episode.dubUrl
        }?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                ResolveException.NotFound(
                    "episode ${episode.id} has no ${audio.name.lowercase()} source",
                ),
            )
        return resolverProvider().resolve(embedUrl, audio)
    }

    /**
     * The second source needs an AniList id rather than an embed URL, so it is asked only when the
     * primary chain has already given up. A missing id is a normal outcome (Hindi rows have none),
     * not an error.
     */
    private suspend fun resolveAlternative(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
        languageId: String?,
    ): Result<PlayableStream> {
        val source = alternative
            ?: return Result.failure(ResolveException.NotFound("no alternative server"))
        val anilistId = runCatching { anilistIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(ResolveException.NotFound("title has no AniList id"))
        // The primary server names its languages "sub"/"dub"; the alternative speaks ISO codes.
        val altLanguage = when (languageId) {
            null, "sub" -> null
            "dub" -> "en"
            else -> languageId
        }
        return source.resolve(
            anilistId = anilistId,
            episode = episode,
            audio = audio,
            languageId = altLanguage,
            seasonHint = seasonHintOf(animeId),
            year = yearOf(animeId),
        )
            .onFailure { error ->
                AppLog.w("alternative source failed for ep${episode.id}: ${error.message}")
            }
    }

    override suspend fun invalidate(episodeId: Long) {
        synchronized(lock) {
            cache.keys.removeAll { key -> key.episodeId == episodeId }
        }
        // The id spaces never overlap, so a blind two-sided invalidate is cheaper than asking the
        // delegates whether they own the episode — same reasoning as [RoutingStreamRepository].
        hindi()?.invalidate(episodeId)
        movieLink()?.invalidate(episodeId)
        AppLog.d("invalidated cached streams for ep$episodeId")
    }

    /** Drops everything (e.g. on a hard network change). */
    fun invalidateAll() {
        synchronized(lock) { cache.clear() }
    }

    private fun read(key: Key): PlayableStream? = synchronized(lock) {
        val entry = cache[key] ?: return@synchronized null
        // The key already carries server + language, and a URL change within the TTL is covered by
        // the error path (403/410 -> invalidate), so age is the only thing to check here.
        val expired = clock() - entry.storedAt > ttlMs
        if (expired) {
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

    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val MAX_ENTRIES = 24
        const val INITIAL_CAPACITY = 16
        const val PROBE_TIMEOUT_MS = 10_000L

        /** Bottom of the Hindi space (`-2_000_000_000`); below it belongs to movielink or to nothing. */
        const val HINDI_MIN_ID = -2_000_000_000
    }
}

/**
 * Which track the primary server should be asked for.
 *
 * The language id is the explicit choice from the server chip and wins when it is there; when it is
 * not, the *audio* is the request — this used to default to SUB, which is why DUB on the title page
 * quietly played the original Japanese and nobody could tell why. Hindi and Tamil have no track on
 * this server, so they return null and the caller moves on instead of silently switching audio.
 */
internal fun primaryAudioFor(audio: AudioTrack, languageId: String?): AudioTrack? = when (languageId) {
    null -> audio
    "sub", "ja" -> AudioTrack.SUB
    "dub", "en" -> AudioTrack.DUB
    else -> null
}
