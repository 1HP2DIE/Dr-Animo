package com.animo.app.domain.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream

/**
 * A second way to play an episode, used when the primary embed chain fails or when the user wants
 * another language.
 *
 * The primary resolver works from an embed URL the catalogue gave us; this one only needs the
 * AniList id and an episode number, which is exactly the shape of the multi-language sources. It
 * lives behind an interface so the repository never depends on a concrete provider, and so a dead
 * source is a null dependency rather than a crash.
 */
interface AlternativeStreamSource {

    /**
     * @param anilistId the series id in AniList space (`Anime.aniId`).
     * @param episode the episode being watched; only its in-season number is used.
     * @param audio the user's SUB/DUB preference, mapped to a language by the implementation.
     */
    suspend fun resolve(
        anilistId: String,
        episode: Episode,
        audio: AudioTrack,
        /** A server language id (`hi`, `en`, `ja`, …); null means "server default". */
        languageId: String? = null,
        /** Season from the catalogue title, `null` when the title does not name one. */
        seasonHint: Int? = null,
        /** Catalogue year, used to notice that a season-1 answer is really an older season. */
        year: Int? = null,
    ): Result<PlayableStream>

    /** The languages this server carries for the episode, best-effort. */
    suspend fun languages(
        anilistId: String,
        episode: Episode,
        seasonHint: Int? = null,
        year: Int? = null,
    ): List<com.animo.app.player.AltStreamAudio> = emptyList()
}
