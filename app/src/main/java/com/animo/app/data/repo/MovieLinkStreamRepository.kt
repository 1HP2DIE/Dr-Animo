package com.animo.app.data.repo

import com.animo.app.data.remote.provider.ProviderEpisode
import com.animo.app.data.remote.provider.movielink.MovieLinkBlockedException
import com.animo.app.data.remote.provider.movielink.MovieLinkHttpException
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.ServerOption
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Playback for the movielinkbd catalogue — the **third** id space, below the Hindi range.
 *
 * ## The chain
 * A row carries one `/p/<token>` link per episode (see `MovieLinkProvider`): the token page is the
 * whole player, so this class has nothing to hop through. `episode.subUrl` -> [MovieLinkStreamResolver]
 * -> one progressive MP4. The measured sample publishes one file per title, already `Range`-enabled
 * and DRM-free, which is why there is no variant/quality step here.
 *
 * ## One stream per episode, so half the interface is meaningless
 * [StreamRepository.resolve] takes `audio`, `server` and `languageId` because the original catalogue
 * has three servers and two tracks. This catalogue has **one track and one server per episode**, so
 * all three are ignored — explicitly, and logged when a DUB was asked for, rather than silently
 * filtered somewhere up the stack. [ServerOption] still answers one language, because the player's
 * menu must not show an empty list, but that language is never sent anywhere.
 *
 * ## Cache
 * Same policy as [StreamRepositoryImpl] and the Hindi repository so the three cannot drift: 15
 * minutes, at most 24 entries, LRU, keyed by `episodeId` **plus** the player link that produced the
 * entry. The link is part of the identity because the index re-hosts titles: a re-synced row can
 * point at a new `/p/` token, and reusing the old stream would play a dead URL for up to 15 minutes.
 *
 * ## Failure contract
 * `resolve` never throws; cancellation is the single exception, because a cancelled resolve must not
 * become a cached-looking failure. Every other error is mapped onto [ResolveException] by [classify]
 * (403 -> [ResolveException.Blocked], 404/410 -> [ResolveException.NotFound], page shape ->
 * [ResolveException.Parse], transport -> [ResolveException.Network]).
 *
 * Plain JVM (no `android.*`), so it is unit-testable without Robolectric.
 *
 * @param resolverProvider lazy resolver: the player module is compiled separately and this class must
 *   not know its protocol.
 * @param episodesOf the provider's episode list for a `"movie/<slug>"`-style id. It is only consulted
 *   when a row has no link of its own (a row written before the episode list was stored); in
 *   production this is `MovieLinkProvider::episodes`.
 * @param seriesIdOf the provider page id of a catalogue row (the row's `originUrl`), read lazily so
 *   this layer never touches Room.
 * @param ttlMs cache lifetime; injectable so tests do not have to sleep 15 minutes.
 * @param clock wall clock for the TTL. `System.currentTimeMillis()` because `android.os.SystemClock`
 *   would drag `android.*` into a JVM-testable class.
 * @param log one-line trace sink; a no-op by default so this file stays `AppLog`-free and testable.
 */
class MovieLinkStreamRepository(
    private val resolverProvider: () -> StreamResolver,
    private val episodesOf: suspend (seriesId: String) -> List<ProviderEpisode> = { emptyList() },
    private val seriesIdOf: suspend (animeId: Int) -> String? = { null },
    /**
     * The title's **current** `/p/<token>` (see `MovieLinkProvider.playerLink`).
     *
     * A stored link is a rotating token: measured on 2026-11-26, a token six days old answers
     * `400 bad config` while the token on the title page right now answers `200`. When the origin
     * calls a link stale this is what re-fetches a usable one.
     */
    private val playerLinkOf: suspend (seriesId: String) -> String? = { null },
    /** Writes a refreshed link back onto the episode row, so the next play needs no refresh. */
    private val onLinkRefreshed: suspend (episodeId: Long, link: String) -> Unit = { _, _ -> },
    private val ttlMs: Long = CACHE_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) : StreamRepository {

    private data class Key(val episodeId: Long)

    private class Entry(
        val stream: PlayableStream,
        val link: String,
        val storedAt: Long,
    )

    private val mutex = Mutex()

    private val lock = Any()

    /** Access-ordered so the eviction policy is LRU rather than arbitrary. */
    private val cache = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, 0.75f, true)

    // ------------------------------------------------------------------ StreamRepository

    /**
     * A movielink title has exactly one server (the index's own `/p/` player) and one track. No probe
     * is issued: a probe here is a full 13 KB page fetch, and the measurement is that every synced
     * row carries a link — so the menu answers without touching the network.
     */
    override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> {
        if (!MovieLinkIds.isMovieLink(animeId)) return emptyList()
        return listOf(
            ServerOption(
                server = StreamServer.PRIMARY,
                languages = listOf(
                    ServerLanguage(id = "bn", label = "Original", audio = AudioTrack.SUB, languageCode = "bn"),
                ),
                probed = true,
                reachable = true,
            ),
        )
    }

    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> = resolve(animeId, episode, audio, server = null, languageId = null)

    /**
     * The server/language parameters are part of the interface, not of this catalogue: they are
     * accepted and ignored (see the class KDoc). Only the episode and the link behind it matter.
     */
    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
        server: StreamServer?,
        languageId: String?,
    ): Result<PlayableStream> {
        if (!MovieLinkIds.isMovieLink(animeId)) {
            // Not a ResolveException on purpose, exactly like the Hindi repository: an id outside the
            // range is a routing bug, and the router only sends this space here.
            return Result.failure(
                IllegalArgumentException(
                    "not a movielink id: $animeId (expected ${MovieLinkIds.MAX_ID}..${MovieLinkIds.MIN_ID})",
                ),
            )
        }

        val key = Key(episode.id)
        val direct = episode.subUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) {
            read(key, direct)?.let {
                log("movielink cache hit ep${episode.id}")
                return Result.success(it)
            }
        }

        return mutex.withLock {
            // Second check: another caller may have resolved while this one queued for the lock.
            if (direct != null) {
                read(key, direct)?.let {
                    log("movielink cache hit after lock ep${episode.id}")
                    return@withLock Result.success(it)
                }
            }

            val link = direct
                ?: linkOf(animeId, episode).getOrElse { return@withLock Result.failure(it) }

            read(key, link)?.let { return@withLock Result.success(it) }

            attempt(animeId, episode, link, audio)
                // The server tag is the truth the menu shows; one server exists, so it is PRIMARY.
                .map { stream -> stream.copy(server = StreamServer.PRIMARY) }
                .onSuccess { stream ->
                    store(key, Entry(stream, link, clock()))
                    log("movielink resolved ep${episode.id} ${stream.container} ${stream.masterUrl.take(48)}")
                }
                .onFailure { error -> log("movielink failed ep${episode.id}: ${error.message}") }
        }
    }

    override suspend fun invalidate(episodeId: Long) {
        synchronized(lock) {
            cache.keys.removeAll { key -> key.episodeId == episodeId }
        }
        log("movielink invalidated cached streams for ep$episodeId")
    }

    /** Drops everything (e.g. on a hard network change, or when the index starts answering 403). */
    fun invalidateAll() {
        synchronized(lock) { cache.clear() }
        log("movielink invalidated every cached stream")
    }

    // ------------------------------------------------------------------ catalogue

    /**
     * Episode rows for one movielink title, for `AnimeRepositoryImpl.ensureEpisodes`.
     *
     * There is no episode API: the rows come from the title page's `#mlbdEpisodeDownloads` grid, and
     * each row's `subUrl` is the `/p/<token>` link the resolver plays. `dubUrl` is always `null`
     * because the index publishes a single track — writing a dub link would make the DUB chip appear
     * and then fail.
     *
     * Ids are derived from `(animeId, number)` by [MovieLinkIds.episodeId], so a second sync re-upserts
     * the same rows instead of duplicating the list.
     *
     * @param originUrl the row's provider page (absolute URL or `"movie/<slug>"`): the provider accepts
     *   either and discards the host, so a row that survived a mirror move still reads.
     */
    suspend fun episodes(animeId: Int, originUrl: String): Result<List<Episode>> {
        if (!MovieLinkIds.isMovieLink(animeId)) {
            return Result.failure(IllegalArgumentException("not a movielink id: $animeId"))
        }
        val seriesId = originUrl.trim().takeIf { it.isNotEmpty() }
            ?: return Result.failure(ResolveException.NotFound("movielink title $animeId has no provider page"))

        return try {
            val entries = episodesOf(seriesId)
            val rows = MovieLinkEpisodes.rows(animeId, entries, System.currentTimeMillis() / 1000L)
            if (rows.isEmpty()) {
                log("$seriesId published no playable episode")
                Result.failure(ResolveException.NotFound("$seriesId published no playable episode"))
            } else {
                log("$seriesId: ${rows.size} episode rows")
                Result.success(rows)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(classify(error))
        }
    }

    // ------------------------------------------------------------------ resolution

    /**
     * One resolve, plus the self-heal a rotating token needs.
     *
     * A [ResolveException.Stale] means the origin no longer decrypts this `/p/<token>` — the token is
     * not an id and the index rotates it. The stored row keeps the dead token forever, so this is the
     * only place that can recover: read a current token from the title page, replay once, and write
     * the working link back onto the row so the next play is direct again.
     *
     * Exactly one retry: a second 400 means the page now issues tokens the shell rejects, which is a
     * provider change and not something a loop can fix.
     */
    private suspend fun attempt(
        animeId: Int,
        episode: Episode,
        link: String,
        audio: AudioTrack,
    ): Result<PlayableStream> {
        val first = attempt(link, audio)
        val error = first.exceptionOrNull()
        if (error !is ResolveException.Stale) return first

        val seriesId = runCatching { seriesIdOf(animeId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return first
        val refreshed = runCatching { playerLinkOf(seriesId) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return first

        val joined = withEpisode(refreshed, episode.number.coerceAtLeast(1))
        if (joined == link) return first

        log("movielink ep${episode.id}: stale player token, refreshing")
        return attempt(joined, audio).onSuccess {
            runCatching { onLinkRefreshed(episode.id, joined) }
                .onFailure { cause -> log("movielink ep${episode.id}: link backfill failed: ${cause.message}") }
            log("movielink ep${episode.id}: player token refreshed and stored")
        }
    }

    /** Carries the episode index onto a refreshed link; a link that already has one is left alone. */
    private fun withEpisode(link: String, index: Int): String {
        if (EPISODE_PARAM.containsMatchIn(link)) return link
        return link + (if (link.contains('?')) "&" else "?") + "ep=$index"
    }

    private suspend fun attempt(link: String, audio: AudioTrack): Result<PlayableStream> = try {
        // The single file is the title's own track. Asking an audio-aware resolver for a dub it does
        // not carry is how the link gets rejected, so SUB is always the request and always the label.
        val stream = resolverProvider().resolve(link, AudioTrack.SUB).getOrElse { throw it }
        if (audio == AudioTrack.DUB) {
            log("movielink publishes one track per episode; serving it for the dub request")
        }
        Result.success(stream.copy(audio = AudioTrack.SUB))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(classify(error))
    }

    /**
     * Fallback for a row that was written before the link was stored: ask the provider for the title's
     * episode list and take the row with this episode's number.
     */
    private suspend fun linkOf(animeId: Int, episode: Episode): Result<String> {
        val seriesId = seriesIdOf(animeId)?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                ResolveException.NotFound("episode ${episode.id} has no movielink player link"),
            )

        val entries = try {
            episodesOf(seriesId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Result.failure(classify(error))
        }

        val row = entries.firstOrNull { it.number == episode.number }
            // A film publishes exactly one entry, numbered 1 by the provider.
            ?: entries.singleOrNull()?.takeIf { episode.number <= 1 }

        val link = row?.subUrl?.trim()?.takeIf { it.isNotEmpty() }
        return link?.let { Result.success(it) }
            ?: Result.failure(
                ResolveException.NotFound("$seriesId published no link for ep${episode.number}"),
            )
    }

    // ------------------------------------------------------------------ failure mapping

    /**
     * Provider and resolver errors -> the player's vocabulary.
     *
     * The provider's errors are typed ([MovieLinkBlockedException] is a Cloudflare challenge or a 403,
     * [MovieLinkHttpException] carries the status), so the mapping is a `when` rather than message
     * sniffing — except for the parse markers, which are our own words and the only signal that the
     * page changed shape instead of the socket failing.
     *
     * Order matters: both exception types extend [IOException], so they are checked first.
     */
    internal fun classify(error: Throwable): ResolveException = when (error) {
        // Already in the player's vocabulary (`MovieLinkStreamResolver` maps its own HTTP).
        is ResolveException -> error

        is MovieLinkBlockedException ->
            ResolveException.Blocked("movielink blocked (${error.status}): ${error.message}")

        is MovieLinkHttpException -> when (error.status) {
            // A challenge page, not a stale token: retryable on another attempt, terminal per request.
            HTTP_FORBIDDEN -> ResolveException.Blocked("movielink HTTP 403")
            // 404/410 are definitive: the title or the token is gone, so no retry can help.
            HTTP_NOT_FOUND, HTTP_GONE -> ResolveException.NotFound("movielink HTTP ${error.status}")
            else -> ResolveException.Network(IOException("movielink HTTP ${error.status}", error))
        }

        is IOException -> {
            val message = error.message.orEmpty()
            if (PARSE_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }) {
                ResolveException.Parse(message)
            } else {
                ResolveException.Network(error)
            }
        }

        else -> ResolveException.Parse("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }

    // ------------------------------------------------------------------ cache

    private fun read(key: Key, link: String): PlayableStream? = synchronized(lock) {
        val entry = cache[key] ?: return@synchronized null
        // Age and identity: a re-synced row can point at a new `/p/` token within the TTL.
        val expired = clock() - entry.storedAt > ttlMs
        val linkChanged = entry.link != link
        if (expired || linkChanged) {
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

        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410

        /** The `?ep=` parameter the player resolver reads to pick one entry of a series shell. */
        val EPISODE_PARAM = Regex("[?&]ep=\\d+")

        /** Provider messages that mean "the page no longer has the shape this parser expects". */
        val PARSE_MARKERS = listOf(
            "not a movielink page id",
            "unusable path",
            "not configured",
            "no player link",
            "no playable",
        )
    }
}

/**
 * The movielink id space and its episode keys.
 *
 * `isMovieLink` is a local copy of `com.animo.app.domain.model.CatalogIds.isMovieLink`, which another
 * agent is landing in parallel: the two comparisons below are the same constants, so switching to the
 * shared object later is a rename and not a behaviour change.
 */
internal object MovieLinkIds {

    /**
     * The band itself lives in [com.animo.app.domain.model.CatalogIds] so the catalogue writer and
     * the reader cannot drift apart; this object only adds the episode-key scheme, which is a
     * playback concern.
     */
    const val MIN_ID: Int = com.animo.app.domain.model.CatalogIds.MOVIE_LINK_MIN
    const val MAX_ID: Int = com.animo.app.domain.model.CatalogIds.MOVIE_LINK_MAX

    fun isMovieLink(id: Int): Boolean = com.animo.app.domain.model.CatalogIds.isMovieLink(id)

    /**
     * Episode primary key: `-(|animeId| * 1000 + number)` — the same block scheme `HindiIds` uses, for
     * the same reasons, and it stays clear of both other id spaces:
     *
     *  - original-catalogue ids are positive;
     *  - Hindi: `|animeId| <= 1_999_999_999` -> `|id| <= 1_999_999_999_999`;
     *  - movielink: `|animeId| >= 2_000_000_001` -> `|id| >= 2_000_000_001_001`.
     *
     * Deterministic by construction, so re-syncing a title re-upserts its rows instead of writing a
     * duplicate list under fresh ids. `number` is clamped into the block, so a page that publishes a
     * bogus four-digit number cannot borrow another series' range.
     */
    fun episodeId(animeId: Int, number: Int): Long {
        val series = abs(animeId.toLong())
        val slot = number.coerceIn(1, (EPISODE_BLOCK - 1).toInt()).toLong()
        return -(series * EPISODE_BLOCK + slot)
    }

    private const val EPISODE_BLOCK = 1_000L
}

/** Provider episode rows -> catalogue rows. Pure, so the numbering rules are testable on their own. */
internal object MovieLinkEpisodes {

    fun rows(animeId: Int, entries: List<ProviderEpisode>, nowEpochSeconds: Long): List<Episode> {
        val byNumber = LinkedHashMap<Int, Episode>(entries.size)
        entries.forEach { entry ->
            val link = entry.subUrl?.trim().orEmpty()
            // A row without a link has nothing to play: storing it would put a dead cell in the grid.
            if (link.isEmpty()) return@forEach
            val number = entry.number.coerceAtLeast(1)
            if (byNumber.containsKey(number)) return@forEach

            byNumber[number] = Episode(
                id = MovieLinkIds.episodeId(animeId, number),
                animeId = animeId,
                number = number,
                title = entry.title,
                embedId = entry.remoteId.orEmpty(),
                subUrl = link,
                // One track per title on this index; a dub link would be a chip that cannot play.
                dubUrl = null,
                updatedAtEpoch = nowEpochSeconds,
            )
        }
        return byNumber.values.sortedBy { it.number }
    }
}
