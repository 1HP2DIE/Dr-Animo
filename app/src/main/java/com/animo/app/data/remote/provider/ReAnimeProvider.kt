package com.animo.app.data.remote.provider

import com.animo.app.domain.model.AudioTrack

/**
 * P0 second source — documented, **not ported**.
 *
 * Recipe to implement (research/PROVIDER_MATRIX.md "ReAnime — port first"):
 * ```
 * GET {base}/api/v1/search?limit=20&typeahead=1&q={q}
 * GET {base}/api/flix/{anilistId}/{ep}     Referer: {base}/watch/{animeId}?ep={n}
 *      -> servers[] with dataType: "dub" | "sub", dataLink
 * GET {dataLink} -> /e/{aid}
 * GET https://flixcloud.cc/d/{aid}/__data.json -> fileId(UUID), token(JWT), fetch{N}.flixcloud.cc
 * GET {base}/download/{fileId}/progress?token=      (poll <=3x @500ms until status=="ready")
 * GET {base}/download/{fileId}?token=…&file=….mkv
 * ```
 * Value: a real `dubbed` boolean, `is_filler`, per-episode thumbnails, and an `anilist_id` on every
 * series — the clean join key for our Anikoto-backed catalog (`ani_id` mixes MAL/AniList spaces).
 * Dub subtitles prefer Signs/Songs/Forced and arrive in MKV, so the player must handle MKV.
 */
class ReAnimeProvider : AnimeProvider {

    override val id: String = PROVIDER_ID

    // TODO(reanime): port the recipe above. Requires a JSON client (no crypto) plus MKV playback.
    override suspend fun search(q: String, page: Int): List<ProviderHit> = notImplemented()

    override suspend fun episodes(seriesId: String): List<ProviderEpisode> = notImplemented()

    override suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack): PlaybackSource =
        notImplemented()

    private fun notImplemented(): Nothing = throw ProviderNotImplementedException(PROVIDER_ID)

    private companion object {
        const val PROVIDER_ID = "reanime"
    }
}
