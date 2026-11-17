package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.repo.AlternativeStreamSource
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Alternative stream server: a multi-language anime embed keyed by **AniList id**.
 *
 * Why this exists: the primary embed chain only knows the URL the catalogue handed us, and that URL
 * is usually one audio track. This server answers with the same episode in up to seven audio tracks
 * (Hindi, English, Tamil, Telugu, Malayalam, Kannada, Japanese on the titles measured), all muxed
 * into one MPEG-TS stream — so the in-player audio picker can switch language without a reload.
 *
 * Verified against the live service on 2026-11-17:
 *  - plaintext JSON (no crypto layer on this path), no session token required;
 *  - 100 rotating API shards, selected by FNV-1a over `anime-{id}-1-{episode}`;
 *  - the raw CDN segments are MPEG-TS served as `.jpg`/`image/jpeg` (0x47 sync every 188 bytes),
 *    which Media3 would hand to a JPEG extractor — hence playback goes through the service's own
 *    proxy URL, which re-serves the same bytes as `video/mp2t`.
 *
 * Every host comes from [ProviderVault]; nothing here is a literal.
 */
class AltStreamSource(
    private val client: OkHttpClient,
    private val shards: () -> List<String> = {
        ProviderVault.list(ProviderKey.STREAM_SOURCE_B_SHARDS)
    },
    private val origin: () -> String = {
        ProviderVault.optionalUrl(ProviderKey.STREAM_SOURCE_B_ORIGIN).orEmpty()
    },
    private val playbackUserAgent: () -> String = {
        ProviderVault.optionalUrl(ProviderKey.STREAM_SOURCE_B_USER_AGENT).orEmpty()
    },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AlternativeStreamSource {

    /** A probe doubles as resolution data: one request serves the menu and the playback. */
    private class Cached(val atMs: Long, val payload: AltStreamPayload, val shard: String)

    private val cache = ConcurrentHashMap<String, Cached>()

    override suspend fun languages(
        anilistId: String,
        episode: Episode,
        seasonHint: Int?,
        year: Int?,
    ): List<AltStreamAudio> = withContext(dispatcher) {
        val loaded = load(anilistId, episode, seasonHint ?: 1, audioCode = null) ?: return@withContext emptyList()
        if (!seasonPlausible(loaded.payload, seasonHint, year)) return@withContext emptyList()
        loaded.payload.audioTracks
    }

    override suspend fun resolve(
        anilistId: String,
        episode: Episode,
        audio: AudioTrack,
        languageId: String?,
        seasonHint: Int?,
        year: Int?,
    ): Result<PlayableStream> = withContext(dispatcher) {
        val audioCode = languageId?.takeIf { it.isNotBlank() }
            ?: AltStreamProtocol.preferredAudio(audio)

        val loaded = load(anilistId, episode, seasonHint ?: 1, audioCode)
            ?: return@withContext Result.failure(
                ResolveException.NotFound("no playable source for this episode"),
            )

        if (!seasonPlausible(loaded.payload, seasonHint, year)) {
            return@withContext Result.failure(
                ResolveException.NotFound("this server only has an earlier season"),
            )
        }

        val source = AltStreamProtocol.pick(loaded.payload)
            ?: return@withContext Result.failure(
                ResolveException.NotFound("server returned no stream"),
            )

        val renditions = AltStreamProtocol.alternates(loaded.payload, source).map { entry ->
            com.animo.app.domain.model.StreamAlternate(
                label = entry.quality,
                height = AltStreamProtocol.heightOf(entry.quality) ?: 0,
                url = AltStreamProtocol.playlistUrl(loaded.shard, entry),
            )
        }
        Result.success(stream(loaded.shard, source, audio).copy(alternates = renditions))
    }

    /**
     * One shard request, cached for [CACHE_TTL_MS] per `(series, episode, audio)`.
     *
     * The audio code is part of the key on purpose: the server re-orders `sources[]` by it and bakes
     * `defaultAudio` into the proxy paths, so a cached probe (no audio) must not be reused for a
     * specific language.
     */
    private fun load(
        anilistId: String,
        episode: Episode,
        season: Int,
        audioCode: String?,
    ): Cached? {
        val id = anilistId.trim()
        if (id.isBlank()) return null

        val key = "$id:$season:${episode.number}:${audioCode ?: "-"}"
        cache[key]?.takeIf { now() - it.atMs < CACHE_TTL_MS }?.let { return it }

        val available = shards().map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        if (available.isEmpty()) return null

        val start = AltStreamProtocol.shardIndex(
            AltStreamProtocol.seed("anime", id, season, episode.number),
            available.size,
        )

        for (offset in 0 until minOf(available.size, MAX_SHARD_ATTEMPTS)) {
            val shard = available[(start + offset) % available.size]
            val url = AltStreamProtocol.streamsUrl(
                shard = shard,
                type = "anime",
                id = id,
                season = season,
                episode = episode.number,
                audio = audioCode,
                timestampMs = now(),
            )
            val body = runCatching { fetch(url) }.getOrNull() ?: continue
            val payload = AltStreamProtocol.parse(body) ?: continue
            if (payload.sources.isEmpty()) continue

            val cached = Cached(now(), payload, shard)
            cache[key] = cached
            return cached
        }
        return null
    }

    /**
     * Guards against the server answering with an earlier season.
     *
     * Its metadata resolves to the *base* show (same title/year for every season), so the only
     * signal is the year: if our title is from a later year and we had no season to ask for, the
     * season-1 asset is almost certainly the wrong video. Refusing is better than playing it.
     */
    private fun seasonPlausible(payload: AltStreamPayload, seasonHint: Int?, year: Int?): Boolean {
        if (seasonHint != null || year == null) return true
        val metaYear = payload.diagnostics?.targetMeta?.year?.toIntOrNull() ?: return true
        return year <= metaYear + 1
    }

    private fun stream(shard: String, source: AltStreamEntry, audio: AudioTrack): PlayableStream =
        PlayableStream(
            masterUrl = AltStreamProtocol.playlistUrl(shard, source),
            // The playlist is served by the source's proxy, so its own origin is the only Referer
            // that host ever sees. The CDN referer is for the raw URL, which we deliberately do not
            // play (its segments are JPEG-labelled TS).
            referer = origin().trimEnd('/') + "/",
            userAgent = playbackUserAgent().ifBlank { Media3Factory.DEFAULT_USER_AGENT },
            audio = audio,
        )

    private fun fetch(url: String): String {
        val originValue = origin()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", playbackUserAgent().ifBlank { Media3Factory.DEFAULT_USER_AGENT })
            .header("Accept", "application/json")
            .apply {
                if (originValue.isNotBlank()) {
                    header("Origin", originValue.trimEnd('/'))
                    header("Referer", originValue.trimEnd('/') + "/")
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        /** A quota-limited account answers 429 on every shard, so there is no point trying all 100. */
        const val MAX_SHARD_ATTEMPTS = 3

        /** A probe is reused for this long, so opening the server list twice does not refetch. */
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
