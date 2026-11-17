package com.animo.app.data.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.repo.StreamRepository

/**
 * Sends a playback request to the right backend.
 *
 * The split is the **sign of the anime id** — the same rule the catalogue uses (`Anime.isHindi`):
 * original-catalogue rows are positive, Hindi-dub rows are negative. One rule, three consumers
 * (browse filter, episode loader, resolver), so there is no flag that can disagree with itself.
 *
 * The Hindi delegate is a provider lambda because the Hindi layer is wired by [com.animo.app.di.ServiceLocator]
 * after the app starts; when it is missing, playback fails with a normal `Result.failure` instead of
 * a crash, exactly like the unavailable original resolver.
 */
class RoutingStreamRepository(
    private val original: StreamRepository,
    private val hindi: () -> StreamRepository?,
) : StreamRepository {

    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
    ): Result<PlayableStream> {
        if (animeId >= 0) return original.resolve(animeId, episode, audio)

        val delegate = hindi()
            ?: return Result.failure(
                IllegalStateException(
                    "Hindi playback is not available in this build (id $animeId)",
                ),
            )
        return delegate.resolve(animeId, episode, audio)
    }

    /**
     * Beats both caches: the id spaces never overlap, so a blind two-sided invalidate is cheaper than
     * asking the delegates whether they own it.
     */
    override suspend fun invalidate(episodeId: Long) {
        original.invalidate(episodeId)
        hindi()?.invalidate(episodeId)
    }
}
