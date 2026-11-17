package com.animo.app.data.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.FallbackStreamSource
import com.animo.app.domain.repo.SourceRequest
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Servers 3+ in the fallback chain, and the checked primary dub. */
class FallbackServersTest {

    private val episode = Episode(
        id = 9L,
        animeId = 1,
        number = 3,
        subUrl = "https://primary.invalid/sub/3",
        dubUrl = "https://primary.invalid/dub/3",
    )

    private fun stream(tag: String) = PlayableStream(
        masterUrl = "https://cdn.invalid/$tag",
        referer = "https://ref.invalid/",
        userAgent = "test",
        audio = AudioTrack.SUB,
    )

    /** Answers per audio track, so sub and dub can live or die separately. */
    private class Resolver(
        val sub: Result<PlayableStream>,
        val dub: Result<PlayableStream>,
    ) : StreamResolver {
        val asked = mutableListOf<AudioTrack>()
        override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> {
            asked += audio
            return if (audio == AudioTrack.DUB) dub else sub
        }
    }

    private class Fallback(
        override val server: StreamServer,
        val result: Result<PlayableStream>,
        val langs: List<ServerLanguage> = emptyList(),
        val slowMs: Long = 0L,
    ) : FallbackStreamSource {
        var calls = 0
        var lastLanguage: String? = "unset"
        var lastRequest: SourceRequest? = null

        override suspend fun languages(request: SourceRequest): List<ServerLanguage> {
            if (slowMs > 0) delay(slowMs)
            return langs
        }

        override suspend fun resolve(request: SourceRequest, audio: AudioTrack, languageId: String?): Result<PlayableStream> {
            calls++
            lastLanguage = languageId
            lastRequest = request
            return result
        }
    }

    private val sub = ServerLanguage("sub", "Original (SUB)", AudioTrack.SUB, "ja")
    private val dub = ServerLanguage("dub", "English (DUB)", AudioTrack.DUB, "en")

    private fun repo(
        resolver: Resolver,
        vararg fallbacks: Fallback,
        probeTimeoutMs: Long = 2_000L,
    ) = StreamRepositoryImpl(
        resolverProvider = { resolver },
        alternative = null,
        anilistIdOf = { "154587" },
        animeTitlesOf = { listOf("Sousou no Frieren", "") },
        animeYearOf = { 2023 },
        fallbacks = fallbacks.toList(),
        probeTimeoutMs = probeTimeoutMs,
    )

    private val down = Result.failure<PlayableStream>(ResolveException.NotFound("down"))

    @Test
    fun `a dead catalogue dub is not offered on server 1`() = runBlocking {
        val resolver = Resolver(Result.success(stream("sub")), Result.failure(ResolveException.Dead("no player")))
        val primary = repo(resolver).serverOptions(1, episode).first { it.server == StreamServer.PRIMARY }
        assertEquals(listOf("sub"), primary.languages.map { it.id })
        assertTrue(AudioTrack.DUB in resolver.asked)
    }

    @Test
    fun `a dub that only failed on the network is still offered`() = runBlocking {
        val resolver = Resolver(Result.success(stream("sub")), Result.failure(ResolveException.Network(RuntimeException("x"))))
        val primary = repo(resolver).serverOptions(1, episode).first { it.server == StreamServer.PRIMARY }
        assertEquals(listOf("sub", "dub"), primary.languages.map { it.id })
    }

    @Test
    fun `every fallback server is listed with its languages`() = runBlocking {
        val resolver = Resolver(Result.success(stream("sub")), Result.success(stream("dub")))
        val options = repo(
            resolver,
            Fallback(StreamServer.MIRROR, down, listOf(sub, dub)),
            Fallback(StreamServer.ARCHIVE, down, emptyList()),
        ).serverOptions(1, episode)

        assertEquals(listOf(1, 3, 4), options.map { it.server.number })
        assertEquals(listOf("sub", "dub"), options[1].languages.map { it.id })
        assertTrue(options[1].reachable)
        assertFalse(options[2].reachable)
    }

    @Test
    fun `a slow server times out as unreachable instead of holding the menu`() = runBlocking {
        val resolver = Resolver(Result.success(stream("sub")), Result.success(stream("dub")))
        val started = System.currentTimeMillis()
        val options = repo(
            resolver,
            Fallback(StreamServer.MIRROR, down, listOf(sub), slowMs = 60_000L),
            probeTimeoutMs = 300L,
        ).serverOptions(1, episode)
        assertTrue(System.currentTimeMillis() - started < 5_000L)
        assertFalse(options.first { it.server == StreamServer.MIRROR }.reachable)
    }

    @Test
    fun `auto falls through the servers in number order`() = runBlocking {
        val mirror = Fallback(StreamServer.MIRROR, down)
        val archive = Fallback(StreamServer.ARCHIVE, Result.success(stream("archive")))
        val result = repo(Resolver(down, down), mirror, archive)
            .resolve(1, episode, AudioTrack.SUB, null, null)

        assertEquals("https://cdn.invalid/archive", result.getOrThrow().masterUrl)
        assertEquals(1, mirror.calls)
        assertEquals(1, archive.calls)
    }

    @Test
    fun `an explicit server 3 is asked first and gets the request context`() = runBlocking {
        val resolver = Resolver(Result.success(stream("sub")), down)
        val mirror = Fallback(StreamServer.MIRROR, Result.success(stream("mirror")))
        val result = repo(resolver, mirror).resolve(1, episode, AudioTrack.DUB, StreamServer.MIRROR, "dub")

        assertEquals("https://cdn.invalid/mirror", result.getOrThrow().masterUrl)
        assertTrue(resolver.asked.isEmpty())
        assertEquals("dub", mirror.lastLanguage)
        assertEquals("154587", mirror.lastRequest?.anilistId)
        assertEquals(listOf("Sousou no Frieren"), mirror.lastRequest?.titles)
    }
}
