package com.animo.app.data.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.AlternativeStreamSource
import com.animo.app.player.AltStreamAudio
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server selection and the auto-fallback chain.
 *
 * The point of these tests is that a dead server is invisible: the viewer picks a language, and if the
 * server that carries it is down the next one is tried — with the language translated into that
 * server's vocabulary (`dub` → `hi`).
 */
class StreamServerOptionsTest {

    private val episode = Episode(
        id = 7L,
        animeId = 1,
        number = 5,
        subUrl = "https://primary.invalid/sub/5",
        dubUrl = "https://primary.invalid/dub/5",
    )

    private val stream = PlayableStream(
        masterUrl = "https://cdn.invalid/master.m3u8",
        referer = "https://primary.invalid/",
        userAgent = "test",
        audio = AudioTrack.SUB,
    )

    private class FakeResolver(
        var result: Result<PlayableStream>,
    ) : StreamResolver {
        var calls = 0
        var lastAudio: AudioTrack? = null

        override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> {
            calls++
            lastAudio = audio
            return result
        }
    }

    private class FakeAlternative(
        var result: Result<PlayableStream>,
        private val tracks: List<AltStreamAudio> = emptyList(),
    ) : AlternativeStreamSource {
        var calls = 0
        var lastLanguage: String? = null

        override suspend fun resolve(
            anilistId: String,
            episode: Episode,
            audio: AudioTrack,
            languageId: String?,
            seasonHint: Int?,
            year: Int?,
        ): Result<PlayableStream> {
            calls++
            lastLanguage = languageId
            lastSeason = seasonHint
            return result
        }

        var lastSeason: Int? = null

        override suspend fun languages(
            anilistId: String,
            episode: Episode,
            seasonHint: Int?,
            year: Int?,
        ): List<AltStreamAudio> = tracks
    }

    private fun repository(
        primary: FakeResolver,
        alternative: FakeAlternative? = null,
    ) = StreamRepositoryImpl(
        resolverProvider = { primary },
        alternative = alternative,
        anilistIdOf = { "154587" },
    )

    // ---------------------------------------------------------------- server list

    @Test
    fun `the primary server lists sub and dub`() = runBlocking {
        val options = repository(FakeResolver(Result.success(stream))).serverOptions(1, episode)

        assertEquals(1, options.size)
        assertEquals(StreamServer.PRIMARY, options[0].server)
        assertEquals(listOf("sub", "dub"), options[0].languages.map { it.id })
        assertEquals("English (DUB)", options[0].languages[1].label)
        assertTrue(options[0].reachable)
    }

    @Test
    fun `a title with no dub only lists the original`() = runBlocking {
        val options = repository(FakeResolver(Result.success(stream)))
            .serverOptions(1, episode.copy(dubUrl = null))

        assertEquals(listOf("sub"), options[0].languages.map { it.id })
    }

    @Test
    fun `an episode with no sources is not reachable`() = runBlocking {
        val options = repository(FakeResolver(Result.success(stream)))
            .serverOptions(1, episode.copy(subUrl = null, dubUrl = null))

        assertFalse(options[0].reachable)
        assertTrue(options[0].languages.isEmpty())
    }

    @Test
    fun `the multi-language server labels the original and maps hindi to dub`() = runBlocking {
        val alternative = FakeAlternative(
            result = Result.success(stream),
            tracks = listOf(
                AltStreamAudio(language = "ja", label = "Japanese", index = 6),
                AltStreamAudio(language = "hi", label = "Hindi", index = 0),
                AltStreamAudio(language = "en", label = "English", index = 5),
            ),
        )

        val options = repository(FakeResolver(Result.success(stream)), alternative)
            .serverOptions(1, episode)

        assertEquals(2, options.size)
        val alt = options[1]
        assertEquals(StreamServer.ALTERNATIVE, alt.server)
        assertEquals(listOf("ja", "hi", "en"), alt.languages.map { it.id })
        assertEquals("Original (Japanese)", alt.languages[0].label)
        assertEquals("Hindi", alt.languages[1].label)
        // Hindi means the dub stream for the player's own bookkeeping.
        assertEquals(AudioTrack.DUB, alt.languages[1].audio)
        assertTrue(alt.reachable)
    }

    @Test
    fun `an unreachable alternative is still listed so the pane can explain it`() = runBlocking {
        val options = repository(FakeResolver(Result.success(stream)), FakeAlternative(Result.success(stream)))
            .serverOptions(1, episode)

        assertEquals(2, options.size)
        assertFalse(options[1].reachable)
    }

    @Test
    fun `a title without an anilist id has only the primary server`() = runBlocking {
        val repo = StreamRepositoryImpl(
            resolverProvider = { FakeResolver(Result.success(stream)) },
            alternative = FakeAlternative(Result.success(stream)),
            anilistIdOf = { null },
        )
        val options = repo.serverOptions(1, episode)
        assertEquals(2, options.size)
        assertFalse(options[1].reachable)
    }

    // ---------------------------------------------------------------- resolution

    @Test
    fun `a failing primary falls through to the alternative`() = runBlocking {
        val primary = FakeResolver(Result.failure(ResolveException.NotFound("down")))
        val alternative = FakeAlternative(Result.success(stream))

        val result = repository(primary, alternative).resolve(1, episode, AudioTrack.SUB)

        assertTrue(result.isSuccess)
        assertEquals(1, primary.calls)
        assertEquals(1, alternative.calls)
    }

    @Test
    fun `picking a language on the alternative passes it through`() = runBlocking {
        val alternative = FakeAlternative(Result.success(stream))

        repository(FakeResolver(Result.success(stream)), alternative)
            .resolve(1, episode, AudioTrack.SUB, StreamServer.ALTERNATIVE, "en")

        assertEquals("en", alternative.lastLanguage)
    }

    @Test
    fun `the catalogue dub is english and is translated as such on the fallback`() = runBlocking {
        val primary = FakeResolver(Result.failure(ResolveException.NotFound("down")))
        val alternative = FakeAlternative(Result.success(stream))

        repository(primary, alternative)
            .resolve(1, episode, AudioTrack.DUB, StreamServer.PRIMARY, "dub")

        // The primary's dub track is English; Hindi dubs are separate catalogue rows.
        assertEquals("en", alternative.lastLanguage)
    }

    @Test
    fun `the primary is not asked when the alternative is chosen outright`() = runBlocking {
        val primary = FakeResolver(Result.success(stream))
        val alternative = FakeAlternative(Result.success(stream))

        repository(primary, alternative)
            .resolve(1, episode, AudioTrack.SUB, StreamServer.ALTERNATIVE, "ja")

        assertEquals(0, primary.calls)
        assertEquals("ja", alternative.lastLanguage)
    }

    @Test
    fun `a language the fallback does not carry is not silently swapped`() = runBlocking {
        val primary = FakeResolver(Result.success(stream))
        val alternative = FakeAlternative(Result.failure(ResolveException.NotFound("down")))

        // Hindi lives on the multi-language server; the primary only has original + English.
        val result = repository(primary, alternative)
            .resolve(1, episode, AudioTrack.DUB, StreamServer.ALTERNATIVE, "hi")

        assertTrue(result.isFailure)
        assertEquals(0, primary.calls)
    }

    @Test
    fun `english falls back to the primary dub`() = runBlocking {
        val primary = FakeResolver(Result.success(stream))
        val alternative = FakeAlternative(Result.failure(ResolveException.NotFound("down")))

        val result = repository(primary, alternative)
            .resolve(1, episode, AudioTrack.SUB, StreamServer.ALTERNATIVE, "en")

        assertTrue(result.isSuccess)
        assertEquals(1, primary.calls)
        assertEquals(AudioTrack.DUB, primary.lastAudio)
    }

    @Test
    fun `the season is read from the title before the server is asked`() = runBlocking {
        val alternative = FakeAlternative(Result.success(stream))
        val repo = StreamRepositoryImpl(
            resolverProvider = { FakeResolver(Result.success(stream)) },
            alternative = alternative,
            anilistIdOf = { "162670" },
            animeTitlesOf = { listOf("Dr. Stone 3rd Season Part 2") },
            animeYearOf = { 2023 },
        )

        repo.resolve(1, episode, AudioTrack.SUB, StreamServer.ALTERNATIVE, "ja")

        assertEquals(3, alternative.lastSeason)
    }

    @Test
    fun `the original language asks the alternative for no specific track`() = runBlocking {
        val primary = FakeResolver(Result.failure(ResolveException.NotFound("down")))
        val alternative = FakeAlternative(Result.success(stream))

        repository(primary, alternative)
            .resolve(1, episode, AudioTrack.SUB, null, "sub")

        assertNull(alternative.lastLanguage)
    }
}
