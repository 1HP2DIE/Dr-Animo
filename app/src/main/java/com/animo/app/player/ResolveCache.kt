package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory resolve cache, keyed by embed URL + audio track.
 *
 * TTL and invalidation rules: research/RESOLVER_SPEC.md §5 — 15 minutes, dropped on any 403/410
 * during playback (the master URL itself carries no token, so only host rotation invalidates it).
 */
class ResolveCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = 32,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Key(val embedUrl: String, val audio: AudioTrack)
    private data class Entry(val stream: PlayableStream, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<Key, Entry>()

    fun get(embedUrl: String, audio: AudioTrack): PlayableStream? {
        val key = Key(embedUrl, audio)
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAtMs > ttlMs) {
            entries.remove(key, entry)
            return null
        }
        return entry.stream
    }

    fun put(embedUrl: String, audio: AudioTrack, stream: PlayableStream) {
        pruneExpired()
        entries[Key(embedUrl, audio)] = Entry(stream, clock())
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.storedAtMs } ?: break
            entries.remove(oldest.key, oldest.value)
        }
    }

    /** Drops every audio variant of [embedUrl] when [audio] is null. */
    fun invalidate(embedUrl: String, audio: AudioTrack? = null) {
        if (audio != null) {
            entries.remove(Key(embedUrl, audio))
            return
        }
        entries.keys.filter { it.embedUrl == embedUrl }.forEach { entries.remove(it) }
    }

    /** Used by the player when a segment/playlist answers 403/410 and we only know the master URL. */
    fun invalidateByMasterUrl(masterUrl: String) {
        entries.entries
            .filter { it.value.stream.masterUrl == masterUrl }
            .forEach { entries.remove(it.key, it.value) }
    }

    fun invalidateAll() {
        entries.clear()
    }

    fun size(): Int = entries.size

    private fun pruneExpired() {
        val now = clock()
        entries.entries
            .filter { now - it.value.storedAtMs > ttlMs }
            .forEach { entries.remove(it.key, it.value) }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 15L * 60L * 1000L

        /** Process-wide cache; players/resolvers never hold their own copy. */
        val shared: ResolveCache = ResolveCache()
    }
}
