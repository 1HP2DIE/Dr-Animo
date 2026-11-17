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
        val key = Key(episode.id, audio, server, languageId)
        read(key)?.let { return Result.success(it) }

        return mutex.withLock {
            read(key)?.let { return@withLock Result.success(it) }

            val order = when (server) {
                StreamServer.PRIMARY -> listOf(StreamServer.PRIMARY, StreamServer.ALTERNATIVE)
                StreamServer.ALTERNATIVE -> listOf(StreamServer.ALTERNATIVE, StreamServer.PRIMARY)
                null -> listOf(StreamServer.PRIMARY, StreamServer.ALTERNATIVE)
            }

            var last: Result<PlayableStream>? = null
            for (candidate in order) {
                val attempt = when (candidate) {
                    StreamServer.PRIMARY -> {
                        // The primary only has the original and the English dub. Falling back to it
                        // for a language it does not carry (Hindi, Tamil, …) would silently switch
                        // the audio, which is worse than saying the episode is unavailable there.
                        val primaryAudio = primaryAudioFor(languageId)
                        if (primaryAudio == null) {
                            Result.failure(
                                ResolveException.NotFound("that language is not on this server"),
                            )
                        } else {
                            resolvePrimary(episode, primaryAudio)
                        }
                    }
                    StreamServer.ALTERNATIVE -> resolveAlternative(animeId, episode, audio, languageId)
                }
                if (attempt.isSuccess) {
                    val stream = attempt.getOrThrow()
                    store(key, Entry(stream, clock()))
                    AppLog.d("resolved ep${episode.id} $audio via $candidate/${languageId ?: "-"}")
                    return@withLock Result.success(stream)
                }
                last = attempt
            }

            last ?: Result.failure(ResolveException.NotFound("no server for this episode"))
        }
    }

    /** Every server, with the languages it actually offers for this episode. */
    override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> {
        val primary = ServerOption(
            server = StreamServer.PRIMARY,
            languages = buildList {
                if (!episode.subUrl.isNullOrBlank()) {
                    add(ServerLanguage(id = "sub", label = "Original (SUB)", audio = AudioTrack.SUB))
                }
                if (!episode.dubUrl.isNullOrBlank()) {
                    // The catalogue's dub track is the English dub; Hindi dubs are their own
                    // catalogue rows and reach the player through the Hindi repository.
                    add(
                        ServerLanguage(
                            id = "dub",
                            label = "English (DUB)",
                            audio = AudioTrack.DUB,
                            languageCode = "en",
                        ),
                    )
                }
            },
            probed = true,
            reachable = !episode.subUrl.isNullOrBlank() || !episode.dubUrl.isNullOrBlank(),
        )

        val alternative = alternative ?: return listOf(primary)
        val anilistId = runCatching { anilistIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return listOf(
                primary,
                ServerOption(StreamServer.ALTERNATIVE, probed = true, reachable = false),
            )

        val tracks = runCatching {
            alternative.languages(anilistId, episode, seasonHintOf(animeId), yearOf(animeId))
        }.getOrDefault(emptyList())
        return listOf(
            primary,
            ServerOption(
                server = StreamServer.ALTERNATIVE,
                languages = tracks.map { track ->
                    val code = track.language.ifBlank { "und" }
                    ServerLanguage(
                        id = code,
                        label = when (code) {
                            "ja" -> "Original (Japanese)"
                            "hi" -> "Hindi"
                            "en" -> "English"
                            else -> track.label.ifBlank { code.uppercase() }
                        },
                        audio = if (code == "hi") AudioTrack.DUB else AudioTrack.SUB,
                        languageCode = code,
                    )
                },
                probed = true,
                reachable = tracks.isNotEmpty(),
            ),
        )
    }

    /**
     * Which primary track can satisfy a requested language: the original track speaks for `sub`/`ja`,
     * the English dub for `dub`/`en`. Anything else has no equivalent on that server.
     */
    private fun primaryAudioFor(languageId: String?): AudioTrack? = when (languageId) {
        null, "sub", "ja" -> AudioTrack.SUB
        "dub", "en" -> AudioTrack.DUB
        else -> null
    }

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
    }
}
