package com.animo.app.data.remote.provider

import com.animo.app.domain.model.AudioTrack

/**
 * Provider abstraction from research/PROVIDER_MATRIX.md.
 *
 * Priority chain per episode is `anikoto -> reanime (if anilist id known) -> animegg`; the first
 * non-dead result wins and the working provider is remembered per series so later episodes skip the
 * chain. Only Anikoto is implemented today (P0, largest catalog at 9,404 series).
 */
interface AnimeProvider {

    /** Stable machine id: "anikoto" | "reanime" | "animegg". */
    val id: String

    suspend fun search(q: String, page: Int = 1): List<ProviderHit>

    suspend fun episodes(seriesId: String): List<ProviderEpisode>

    suspend fun resolve(episode: ProviderEpisode, audio: AudioTrack = AudioTrack.SUB): PlaybackSource
}

/** A provider-local search result, before it is joined onto our Room catalog. */
data class ProviderHit(
    val providerId: String,
    val seriesId: String,
    val title: String,
    val poster: String = "",
    val year: Int? = null,
    /** ReAnime carries this per series; Anikoto does not (its `ani_id` mixes id spaces). */
    val anilistId: Int? = null,
    val malId: Int? = null,
)

/** One episode as the provider describes it, with both audio tracks when the provider splits them. */
data class ProviderEpisode(
    val providerId: String,
    val seriesId: String,
    val number: Int,
    val title: String = "",
    val subUrl: String? = null,
    val dubUrl: String? = null,
    /** Server-side episode id when the provider has one (Anikoto: `episodes[].id`). */
    val remoteId: String? = null,
)

/** A resolved, directly playable media source plus the headers it requires. */
data class PlaybackSource(
    val masterUrl: String,
    val referer: String,
    val userAgent: String,
    val audio: AudioTrack,
    val isHls: Boolean = true,
)

/** Thrown by provider stubs that are documented but not ported yet. */
class ProviderNotImplementedException(providerId: String) :
    UnsupportedOperationException("provider '$providerId' is not implemented yet")
