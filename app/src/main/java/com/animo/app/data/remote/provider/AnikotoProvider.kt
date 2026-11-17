package com.animo.app.data.remote.provider

import com.animo.app.AppLog
import com.animo.app.data.remote.AnikotoApi
import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.data.remote.Normalize
import com.animo.app.domain.model.AudioTrack
import com.animo.app.player.StreamResolver

/**
 * P0 provider: the Anikoto catalog API plus the megaplay embed chain.
 *
 * Anikoto has **no server-side search endpoint** — only `recent-anime` pages and `series/{id}`. The
 * app's real search is local FTS over Room (ARCHITECTURE.md "Search"), so [search] here is a
 * best-effort single-page scan used only by the provider chain, never by the UI.
 *
 * [resolve] deliberately delegates the embed -> master.m3u8 chain (AES-256-CBC, Referer, cachebuster)
 * to the injected [StreamResolver]: this class must not know the playback protocol, and the player
 * module must be replaceable without touching the catalog layer.
 */
class AnikotoProvider(
    private val api: AnikotoApi,
    private val resolverProvider: () -> StreamResolver,
    private val userAgent: String = HttpClientFactory.UA_DESKTOP,
) : AnimeProvider {

    override val id: String = PROVIDER_ID

    override suspend fun search(q: String, page: Int): List<ProviderHit> {
        val needle = Normalize.cleanText(q).lowercase()
        if (needle.isEmpty()) return emptyList()

        val response = api.recentAnime(page.coerceAtLeast(1)).getOrElse { error ->
            AppLog.w("anikoto search page $page failed: ${error.message}")
            return emptyList()
        }

        return response.data.mapNotNull { dto ->
            val title = Normalize.cleanText(dto.title)
            val alternative = Normalize.cleanText(dto.alternative)
            val native = Normalize.cleanText(dto.native)
            val aliases = Normalize.cleanText(dto.titles)
            val haystack = "$title\u0000$alternative\u0000$native\u0000$aliases".lowercase()
            if (!haystack.contains(needle)) {
                null
            } else {
                ProviderHit(
                    providerId = PROVIDER_ID,
                    seriesId = dto.id.toString(),
                    title = title,
                    poster = dto.poster,
                    year = dto.year,
                    anilistId = null,
                    malId = Normalize.parseIntOrNull(dto.malId),
                )
            }
        }
    }

    override suspend fun episodes(seriesId: String): List<ProviderEpisode> {
        val animeId = seriesId.toIntOrNull() ?: return emptyList()

        val detail = api.series(animeId).getOrElse { error ->
            AppLog.w("anikoto episodes($seriesId) failed: ${error.message}")
            return emptyList()
        }

        return detail.episodes.map { dto ->
            ProviderEpisode(
                providerId = PROVIDER_ID,
                seriesId = seriesId,
                number = dto.number,
                title = Normalize.cleanText(dto.title),
                subUrl = (dto.embedUrl.sub ?: dto.embedUrl.hsub)?.trim()?.takeIf { it.isNotEmpty() },
                dubUrl = dto.embedUrl.dub?.trim()?.takeIf { it.isNotEmpty() },
                remoteId = dto.id.toString(),
            )
        }
    }

    override suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack): PlaybackSource {
        val embedUrl = when (audio) {
            AudioTrack.SUB -> episode.subUrl
            AudioTrack.DUB -> episode.dubUrl
        } ?: throw ProviderResolveException("no ${audio.name.lowercase()} embed for ${episode.seriesId}#${episode.number}")

        val stream = resolverProvider().resolve(embedUrl, audio).getOrElse { error ->
            throw ProviderResolveException("resolver: ${error.message}", error)
        }

        return PlaybackSource(
            masterUrl = stream.masterUrl,
            referer = stream.referer.ifBlank { embedUrl },
            userAgent = stream.userAgent.ifBlank { userAgent },
            audio = audio,
            isHls = true,
        )
    }

    /** megaplay.buzz served 100% of the 3,425 sampled embed URLs (research/DATA_CONTRACT.md §3). */
    private companion object {
        const val PROVIDER_ID = "anikoto"
    }
}

class ProviderResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
