package com.animo.app.data.repo

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.ServerOption
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.StreamRepository
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id-space routing table, exercised through [StreamRepositoryImpl] with a fake delegate per
 * backend. Nothing here touches the network: the "original" path is a fake resolver, Hindi and
 * MovieLink are recording fakes.
 *
 * The table is the whole point of the test — the three spaces are adjacent, so an off-by-one in any
 * boundary would silently send an episode to the wrong provider and the only symptom in production
 * would be a title that never plays.
 */
class StreamIdRoutingTest {

    private val episode = Episode(
        id = 42L,
        animeId = 1,
        number = 1,
        subUrl = "https://primary.invalid/sub/1",
    )

    private val stream = PlayableStream(
        masterUrl = "https://cdn.invalid/master.m3u8",
        referer = "https://primary.invalid/",
        userAgent = "test",
        audio = AudioTrack.SUB,
    )

    private class FakeBackend(
        val label: String,
    ) : StreamRepository {
        val resolvedFor = mutableListOf<Int>()
        val optionsFor = mutableListOf<Int>()
        val invalidated = mutableListOf<Long>()

        override suspend fun resolve(animeId: Int, episode: Episode, audio: AudioTrack): Result<PlayableStream> =
            resolve(animeId, episode, audio, null, null)

        override suspend fun resolve(
            animeId: Int,
            episode: Episode,
            audio: AudioTrack,
            server: StreamServer?,
            languageId: String?,
        ): Result<PlayableStream> {
            resolvedFor += animeId
            return Result.success(
                PlayableStream(
                    masterUrl = "https://$label.invalid/$animeId.m3u8",
                    referer = "https://$label.invalid/",
                    userAgent = "test",
                    audio = audio,
                ),
            )
        }

        override suspend fun serverOptions(animeId: Int, episode: Episode): List<ServerOption> {
            optionsFor += animeId
            return listOf(
                ServerOption(
                    server = StreamServer.PRIMARY,
                    languages = listOf(ServerLanguage(id = label, label = label, audio = AudioTrack.SUB)),
                    probed = true,
                ),
            )
        }

        override suspend fun invalidate(episodeId: Long) {
            invalidated += episodeId
        }
    }

    private class FakeResolver(private val result: Result<PlayableStream>) : StreamResolver {
        var calls = 0

        override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> {
            calls++
            return result
        }
    }

    private fun routingRepo(
        hindi: StreamRepository? = null,
        movieLink: StreamRepository? = null,
        resolver: StreamResolver = FakeResolver(Result.success(stream)),
    ) = StreamRepositoryImpl(
        resolverProvider = { resolver },
        hindi = { hindi },
        movieLink = { movieLink },
    )

    // ------------------------------------------------------------------ boundaries

    @Test
    fun `every id is claimed by exactly one space`() {
        // The same three rules the dispatch uses, so a boundary change has to fail here first.
        fun spacesOf(id: Int): Int = when {
            id >= 0 -> 1
            MovieLinkIds.isMovieLink(id) -> 1
            id in HINDI_MIN_ID..-1 -> 1
            else -> 0
        }

        val boundaries = listOf(
            Int.MIN_VALUE to 0,
            -2_100_000_001 to 0,
            -2_100_000_000 to 1,
            -2_050_000_000 to 1,
            -2_000_000_001 to 1,
            -2_000_000_000 to 1,
            -1 to 1,
            0 to 1,
            1 to 1,
            Int.MAX_VALUE to 1,
        )
        boundaries.forEach { (id, claimed) ->
            assertEquals("id $id", claimed, spacesOf(id))
        }
        // Adjacent, not overlapping: the Hindi floor is exactly one above the movielink ceiling.
        assertEquals(-2_000_000_000, MovieLinkIds.MAX_ID + 1)
    }

    @Test
    fun `an original id is served by this repository`() {
        val original = FakeResolver(Result.success(stream))
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink, resolver = original)

        val result = runBlocking { repo.resolve(7, episode, AudioTrack.SUB) }

        assertTrue(result.isSuccess)
        assertEquals(1, original.calls)
        assertTrue(hindi.resolvedFor.isEmpty())
        assertTrue(movieLink.resolvedFor.isEmpty())
    }

    @Test
    fun `a hindi id goes to the hindi backend`() {
        val original = FakeResolver(Result.success(stream))
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink, resolver = original)

        listOf(-1, -5_000_000, -2_000_000_000).forEach { id ->
            val result = runBlocking { repo.resolve(id, episode, AudioTrack.DUB) }
            assertEquals("id $id", "https://hindi.invalid/$id.m3u8", result.getOrThrow().masterUrl)
        }

        assertEquals(listOf(-1, -5_000_000, -2_000_000_000), hindi.resolvedFor)
        assertEquals(0, original.calls)
        assertTrue(movieLink.resolvedFor.isEmpty())
    }

    @Test
    fun `a movielink id goes to the movielink backend`() {
        val original = FakeResolver(Result.success(stream))
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink, resolver = original)

        listOf(-2_000_000_001, -2_050_000_000, -2_100_000_000).forEach { id ->
            val result = runBlocking { repo.resolve(id, episode, AudioTrack.SUB) }
            assertEquals("id $id", "https://movielink.invalid/$id.m3u8", result.getOrThrow().masterUrl)
        }

        assertEquals(listOf(-2_000_000_001, -2_050_000_000, -2_100_000_000), movieLink.resolvedFor)
        assertEquals(0, original.calls)
        assertTrue(hindi.resolvedFor.isEmpty())
    }

    @Test
    fun `an id below every space is a typed routing failure`() {
        val original = FakeResolver(Result.success(stream))
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink, resolver = original)

        val error = runBlocking { repo.resolve(Int.MIN_VALUE, episode, AudioTrack.SUB) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, original.calls)
        assertTrue(hindi.resolvedFor.isEmpty())
        assertTrue(movieLink.resolvedFor.isEmpty())
    }

    @Test
    fun `a movielink id without a wired backend fails instead of crashing`() {
        val original = FakeResolver(Result.success(stream))
        val repo = routingRepo(resolver = original)

        val error = runBlocking { repo.resolve(-2_050_000_000, episode, AudioTrack.SUB) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, original.calls)
    }

    @Test
    fun `a hindi id without a wired backend fails instead of crashing`() {
        val repo = routingRepo()

        val error = runBlocking { repo.resolve(-5, episode, AudioTrack.DUB) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `the auto resolve overload routes too`() {
        val hindi = FakeBackend("hindi")
        val repo = routingRepo(hindi = hindi)

        val result = runBlocking { repo.resolve(-5, episode, AudioTrack.DUB) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(-5), hindi.resolvedFor)
    }

    // ------------------------------------------------------------------ menu and cache

    @Test
    fun `the server menu is answered by the owning backend`() {
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink)

        val movieLinkOptions = runBlocking { repo.serverOptions(-2_050_000_000, episode) }
        val hindiOptions = runBlocking { repo.serverOptions(-5, episode) }

        assertEquals("movielink", movieLinkOptions.single().languages.single().id)
        assertEquals("hindi", hindiOptions.single().languages.single().id)
        assertEquals(listOf(-2_050_000_000), movieLink.optionsFor)
        assertEquals(listOf(-5), hindi.optionsFor)
    }

    @Test
    fun `invalidation reaches every backend`() {
        val hindi = FakeBackend("hindi")
        val movieLink = FakeBackend("movielink")
        val repo = routingRepo(hindi = hindi, movieLink = movieLink)

        runBlocking { repo.invalidate(42L) }

        assertEquals(listOf(42L), hindi.invalidated)
        assertEquals(listOf(42L), movieLink.invalidated)
    }

    @Test
    fun `an original failure is reported, not swallowed`() {
        val resolver = FakeResolver(Result.failure(ResolveException.Blocked("403")))
        val repo = routingRepo(resolver = resolver)

        val result = runBlocking { repo.resolve(7, episode, AudioTrack.SUB) }

        // The repository walks its candidates and surfaces the *last* failure, so this asserts that
        // a positive id still ends in a typed resolve error rather than a delegate's "not available"
        // exception (which is what a mis-routed id would produce).
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ResolveException)
    }

    private companion object {
        /** Bottom of the Hindi space; duplicated from the dispatch so the table above is independent. */
        const val HINDI_MIN_ID = -2_000_000_000
    }
}
