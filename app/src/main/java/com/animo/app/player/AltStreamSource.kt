package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.repo.AlternativeStreamSource
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Alternative stream source: a multi-language anime embed keyed by **AniList id**.
 *
 * Why this exists: the primary embed chain only knows the URL the catalogue handed us, and that
 * URL is usually one audio track. This source answers with the same episode in up to seven audio
 * tracks (Hindi, English, Tamil, Telugu, Malayalam, Kannada, Japanese on the titles measured), all
 * muxed into one MPEG-TS stream — so the in-player audio picker can switch language without a
 * reload.
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
    private val playbackReferer: () -> String = {
        ProviderVault.optionalUrl(ProviderKey.STREAM_SOURCE_B_REFERER).orEmpty()
    },
    private val playbackUserAgent: () -> String = {
        ProviderVault.optionalUrl(ProviderKey.STREAM_SOURCE_B_USER_AGENT).orEmpty()
    },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AlternativeStreamSource {

    override suspend fun resolve(
        anilistId: String,
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> = withContext(dispatcher) {
        val id = anilistId.trim()
        if (id.isBlank()) {
            return@withContext Result.failure(ResolveException.Parse("this title has no AniList id"))
        }

        val available = shards().map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
        if (available.isEmpty()) {
            return@withContext Result.failure(
                ResolveException.Parse("alternative source is not configured"),
            )
        }

        // Shard choice is a hash in the site's own client; all shards are stateless, so the rest of
        // the list is a retry order for quota errors and dead hosts.
        val start = AltStreamProtocol.shardIndex(
            AltStreamProtocol.seed("anime", id, SEASON, episode.number),
            available.size,
        )
        val ordered = List(minOf(available.size, MAX_SHARD_ATTEMPTS)) { offset ->
            available[(start + offset) % available.size]
        }

        var lastFailure: Throwable? = null
        for (shard in ordered) {
            val url = AltStreamProtocol.streamsUrl(
                shard = shard,
                type = "anime",
                id = id,
                season = SEASON,
                episode = episode.number,
                audio = AltStreamProtocol.preferredAudio(audio),
                timestampMs = now(),
            )

            val body = try {
                fetch(url)
            } catch (e: IOException) {
                lastFailure = e
                continue
            }

            val payload = AltStreamProtocol.parse(body) ?: continue
            val source = AltStreamProtocol.pick(payload) ?: continue

            return@withContext Result.success(
                PlayableStream(
                    masterUrl = AltStreamProtocol.playlistUrl(shard, source),
                    // The playlist is served by the source's proxy, so its own origin is the only
                    // Referer that host ever sees. The CDN referer is for the raw URL, which we
                    // deliberately do not play (its segments are JPEG-labelled TS).
                    referer = origin().trimEnd('/') + "/",
                    userAgent = playbackUserAgent().ifBlank { Media3Factory.DEFAULT_USER_AGENT },
                    audio = audio,
                ),
            )
        }

        val reason = lastFailure?.message ?: "no playable source for this episode"
        Result.failure(
            if (lastFailure is IOException) {
                ResolveException.Network(lastFailure)
            } else {
                ResolveException.NotFound(reason)
            },
        )
    }

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
        /** Anime is always season 1 in this source's index; the episode number carries the rest. */
        const val SEASON = 1

        /** A quota-limited account answers 429 on every shard, so there is no point trying all 100. */
        const val MAX_SHARD_ATTEMPTS = 3
    }
}
