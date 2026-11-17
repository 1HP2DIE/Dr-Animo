package com.animo.app.data.repo

import android.os.SystemClock
import com.animo.app.AppLog
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
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
) : StreamRepository {

    private data class Key(val episodeId: Long, val audio: AudioTrack)

    private class Entry(
        val stream: PlayableStream,
        val embedUrl: String,
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
    ): Result<PlayableStream> {
        val embedUrl = when (audio) {
            AudioTrack.SUB -> episode.subUrl
            AudioTrack.DUB -> episode.dubUrl
        }?.takeIf { it.isNotBlank() }

        val key = Key(episode.id, audio)
        if (embedUrl != null) read(key, embedUrl)?.let { return Result.success(it) }

        return mutex.withLock {
            // Second check: another caller may have resolved while we queued for the lock.
            if (embedUrl != null) read(key, embedUrl)?.let { return@withLock Result.success(it) }

            val primary = if (embedUrl == null) {
                Result.failure(
                    ResolveException.NotFound(
                        "episode ${episode.id} has no ${audio.name.lowercase()} source",
                    ),
                )
            } else {
                resolverProvider().resolve(embedUrl, audio)
            }

            val result = if (primary.isSuccess) {
                primary
            } else {
                resolveAlternative(animeId, episode, audio) ?: primary
            }

            result
                .onSuccess { stream ->
                    store(key, Entry(stream, embedUrl.orEmpty(), clock()))
                    AppLog.d("resolved ep${episode.id} ${audio.name} -> ${stream.masterUrl}")
                }
                .onFailure { error ->
                    AppLog.w("resolve failed for ep${episode.id} ${audio.name}: ${error.message}")
                }
        }
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
    ): Result<PlayableStream>? {
        val source = alternative ?: return null
        val anilistId = runCatching { anilistIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val result = source.resolve(anilistId, episode, audio)
        result.onFailure { error ->
            AppLog.w("alternative source failed for ep${episode.id}: ${error.message}")
        }
        return result
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

    private fun read(key: Key, embedUrl: String): PlayableStream? = synchronized(lock) {
        val entry = cache[key] ?: return@synchronized null
        val expired = clock() - entry.storedAt > ttlMs
        val urlChanged = entry.embedUrl != embedUrl
        if (expired || urlChanged) {
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
