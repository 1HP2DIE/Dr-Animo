package com.animo.app.domain.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.StreamServer

/** Everything a source may need to find an episode, gathered once per request. */
data class SourceRequest(
    val episode: Episode,
    /** AniList id (`Anime.aniId`), `null` when the catalogue row has none. */
    val anilistId: String?,
    /** Catalogue title first, then its alternative title. */
    val titles: List<String>,
    val year: Int?,
)

/**
 * A further numbered server, tried after the primary and the alternative.
 *
 * Language ids follow the primary's vocabulary — `sub` (original audio) and `dub` (English) — and
 * the ISO codes `ja`/`en` are accepted as synonyms. Any other language is "not on this server",
 * never a silent switch to a different audio.
 */
interface FallbackStreamSource {

    val server: StreamServer

    /** Languages this server has for the episode; empty when it does not have the episode. */
    suspend fun languages(request: SourceRequest): List<ServerLanguage>

    suspend fun resolve(
        request: SourceRequest,
        audio: AudioTrack,
        languageId: String?,
    ): Result<PlayableStream>
}

/** `sub`/`ja` → original, `dub`/`en` → English dub, `null` → the caller's [audio]; else `null`. */
fun wantsDub(languageId: String?, audio: AudioTrack): Boolean? = when (languageId) {
    null -> audio == AudioTrack.DUB
    "sub", "ja" -> false
    "dub", "en" -> true
    else -> null
}
