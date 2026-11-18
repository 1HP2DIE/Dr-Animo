package com.animo.app.download

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerOption
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.StreamRepository

/**
 * A downloaded episode plays from its file — no network, no resolve, no server fallback. Picking a
 * server or language explicitly still goes to the network: that is the viewer asking for a stream.
 */
class OfflineFirstStreamRepository(
    private val network: StreamRepository,
    private val local: (episodeId: Long, audio: AudioTrack) -> PlayableStream?,
) : StreamRepository {

    override suspend fun resolve(animeId: Int, episode: Episode, audio: AudioTrack): Result<PlayableStream> =
        local(episode.id, audio)?.let { Result.success(it) } ?: network.resolve(animeId, episode, audio)

    override suspend fun resolve(
        animeId: Int,
        episode: Episode,
        audio: AudioTrack,
        server: StreamServer?,
        languageId: String?,
    ): Result<PlayableStream> {
        if (server == null && languageId == null) local(episode.id, audio)?.let { return Result.success(it) }
        return network.resolve(animeId, episode, audio, server, languageId)
    }

    override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> =
        network.serverOptions(animeId, episode)

    override suspend fun invalidate(episodeId: Long) = network.invalidate(episodeId)
}
