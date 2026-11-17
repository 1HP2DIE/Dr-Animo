package com.animo.app.data.remote.provider

import com.animo.app.domain.model.AudioTrack

/**
 * P0 fallback — documented, **not ported**.
 *
 * Recipe to implement (research/PROVIDER_MATRIX.md "AnimEgg — port second"):
 * ```
 * GET https://www.animegg.org/search/auto/?q={q}    // JSON: name, url, thumbnailUrl
 * GET {seriesUrl}                                   // div.newmanga children reversed -> episodes
 * GET {watchUrl}                                    // a[data-version=subbed|dubbed][data-id]
 * GET {embedUrl}                                    // regex: var videoSources = (\[[\s\S]*?\]);
 *      -> baseUrl + e['file'], Referer: {embedUrl}
 * ```
 * Value: no crypto at all, per-quality direct mp4 URLs, and an explicit sub/dub flag — the most
 * resilient provider, immune to AES rotation.
 */
class AnimeEggProvider : AnimeProvider {

    override val id: String = PROVIDER_ID

    // TODO(animegg): port the recipe above. HTML scraping + a JSON regex, no crypto.
    override suspend fun search(q: String, page: Int): List<ProviderHit> = notImplemented()

    override suspend fun episodes(seriesId: String): List<ProviderEpisode> = notImplemented()

    override suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack): PlaybackSource =
        notImplemented()

    private fun notImplemented(): Nothing = throw ProviderNotImplementedException(PROVIDER_ID)

    private companion object {
        const val PROVIDER_ID = "animegg"
    }
}
