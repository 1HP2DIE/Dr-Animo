package com.animo.app.data.repo

import com.animo.app.data.remote.provider.ProviderEpisode
import com.animo.app.data.remote.provider.movielink.MovieLinkBlockedException
import com.animo.app.data.remote.provider.movielink.MovieLinkHttpException
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.StreamServer
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The movielink repository, driven with fakes for the resolver and the provider — no network, no
 * Room, no container.
 *
 * Three things are pinned here that a reader of the class cannot verify by eye: the cache key really
 * includes the player link (a re-synced row must not replay a dead token), the single-track catalogue
 * never asks the resolver for a dub, and the failure mapping lands on the exact `ResolveException`
 * subtype the player's error sheet branches on.
 */
class MovieLinkStreamRepositoryTest {

    private val animeId = -2_000_000_500

    private val stream = PlayableStream(
        masterUrl = "https://fsl.movielink.tv/p/tokStream",
        referer = "https://movielinkbd.one/",
        userAgent = "test-agent",
        audio = AudioTrack.SUB,
        container = StreamContainer.PROGRESSIVE,
    )

    private fun episode(number: Int = 3, link: String? = "https://movielinkbd.one/p/tokEp3") = Episode(
        id = MovieLinkIds.episodeId(animeId, number),
        animeId = animeId,
        number = number,
        subUrl = link,
    )

    // ------------------------------------------------------------------ playback

    @Test
    fun `the episode's own link is resolved as the single sub track`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        val resolved = runBlocking { repo.resolve(animeId, episode(), AudioTrack.DUB).getOrThrow() }

        assertEquals("https://movielinkbd.one/p/tokEp3", resolver.urls.single())
        // A DUB request must not reach the resolver: there is one file and asking for a dub rejects it.
        assertEquals(listOf(AudioTrack.SUB), resolver.audios)
        // …and the answer is labelled with the track that actually plays.
        assertEquals(AudioTrack.SUB, resolved.audio)
        assertEquals(StreamServer.PRIMARY, resolved.server)
        assertEquals(StreamContainer.PROGRESSIVE, resolved.container)
    }

    @Test
    fun `server choice and language are accepted and ignored`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        val resolved = runBlocking {
            repo.resolve(animeId, episode(), AudioTrack.SUB, StreamServer.ARCHIVE, "hi").getOrThrow()
        }

        assertEquals(StreamServer.PRIMARY, resolved.server)
        assertEquals(1, resolver.urls.size)
    }

    @Test
    fun `the menu publishes one server with one language`() {
        val repo = MovieLinkStreamRepository(resolverProvider = { FakeResolver(Result.success(stream)) })

        val options = runBlocking { repo.serverOptions(animeId, episode()) }

        assertEquals(1, options.size)
        assertEquals(StreamServer.PRIMARY, options.single().server)
        assertEquals(1, options.single().languages.size)
        assertEquals(AudioTrack.SUB, options.single().languages.single().audio)
        assertTrue(options.single().isUsable)
    }

    @Test
    fun `a blank link is looked up through the provider`() {
        val resolver = FakeResolver(Result.success(stream))
        var askedSeries: String? = null
        val repo = MovieLinkStreamRepository(
            resolverProvider = { resolver },
            episodesOf = { seriesId ->
                askedSeries = seriesId
                listOf(
                    providerEpisode(1, "https://movielinkbd.one/p/tokS1"),
                    providerEpisode(2, "https://movielinkbd.one/p/tokS2"),
                )
            },
            seriesIdOf = { "movie/some-title" },
        )

        val resolved = runBlocking { repo.resolve(animeId, episode(number = 2, link = null), AudioTrack.SUB) }

        assertTrue(resolved.isSuccess)
        assertEquals("movie/some-title", askedSeries)
        assertEquals("https://movielinkbd.one/p/tokS2", resolver.urls.single())
    }

    @Test
    fun `a row without a link and without a provider page is not found`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        val error = runBlocking { repo.resolve(animeId, episode(link = null), AudioTrack.SUB).exceptionOrNull() }

        assertTrue(error is ResolveException.NotFound)
        assertTrue(resolver.urls.isEmpty())
    }

    @Test
    fun `a foreign id is rejected before the resolver is asked`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        val error = runBlocking { repo.resolve(-5, episode(), AudioTrack.SUB).exceptionOrNull() }

        assertTrue(error is IllegalArgumentException)
        assertTrue(resolver.urls.isEmpty())
        assertTrue(runBlocking { repo.serverOptions(-5, episode()) }.isEmpty())
    }

    // ------------------------------------------------------------------ cache

    @Test
    fun `a second resolve of the same episode and link is a cache hit`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        runBlocking {
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
        }

        assertEquals(1, resolver.urls.size)
    }

    @Test
    fun `the cache key carries the link, so a re-hosted episode resolves again`() {
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        runBlocking {
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            // Same episode id, new token: the old stream is dead and must not be replayed.
            repo.resolve(animeId, episode(link = "https://movielinkbd.one/p/tokNew"), AudioTrack.SUB)
                .getOrThrow()
            // …and the old link is not in the cache any more either.
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
        }

        assertEquals(
            listOf(
                "https://movielinkbd.one/p/tokEp3",
                "https://movielinkbd.one/p/tokNew",
                "https://movielinkbd.one/p/tokEp3",
            ),
            resolver.urls,
        )
    }

    @Test
    fun `an entry expires after the ttl and invalidation drops one episode only`() {
        var now = 1_000L
        val resolver = FakeResolver(Result.success(stream))
        val repo = MovieLinkStreamRepository(
            resolverProvider = { resolver },
            ttlMs = 500L,
            clock = { now },
        )

        runBlocking {
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            now += 400L
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            assertEquals(1, resolver.urls.size)

            now += 200L // now past the 500 ms window of the stored entry
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            assertEquals(2, resolver.urls.size)

            repo.invalidate(episode().id)
            repo.resolve(animeId, episode(), AudioTrack.SUB).getOrThrow()
            assertEquals(3, resolver.urls.size)
        }
    }

    @Test
    fun `a failed resolve is not cached`() {
        val resolver = FakeResolver(Result.failure(ResolveException.Blocked("403")))
        val repo = MovieLinkStreamRepository(resolverProvider = { resolver })

        runBlocking {
            assertTrue(repo.resolve(animeId, episode(), AudioTrack.SUB).isFailure)
            assertTrue(repo.resolve(animeId, episode(), AudioTrack.SUB).isFailure)
        }

        assertEquals(2, resolver.urls.size)
    }

    // ------------------------------------------------------------------ stale token

    /**
     * The failure this repository exists to survive: the stored `/p/<token>` was minted days ago and
     * the origin answers `400 bad config`. One refresh from the title page must turn it into a play,
     * and the working link must be written back so the next play is direct.
     */
    @Test
    fun `a stale stored token is refreshed from the title page and the row is backfilled`() {
        val resolver = StaleForOlderTokens()
        var askedSeries: String? = null
        val backfill = mutableListOf<Pair<Long, String>>()
        val repo = MovieLinkStreamRepository(
            resolverProvider = { resolver },
            seriesIdOf = { "series/gopal-bhar" },
            playerLinkOf = { seriesId ->
                askedSeries = seriesId
                "https://movielinkbd.one/p/freshToken"
            },
            onLinkRefreshed = { episodeId, link -> backfill += episodeId to link },
        )

        val resolved = runBlocking {
            repo.resolve(animeId, episode(number = 3, link = "https://movielinkbd.one/p/oldToken?ep=3"), AudioTrack.SUB)
                .getOrThrow()
        }

        assertEquals("series/gopal-bhar", askedSeries)
        // The stale link is tried once, the refreshed one succeeds, and the episode index survives.
        assertEquals(
            listOf("https://movielinkbd.one/p/oldToken?ep=3", "https://movielinkbd.one/p/freshToken?ep=3"),
            resolver.urls,
        )
        assertEquals("https://fsl.movielink.tv/p/freshToken", resolved.masterUrl)
        assertEquals(listOf(episode(number = 3).id to "https://movielinkbd.one/p/freshToken?ep=3"), backfill)
    }

    @Test
    fun `a refresh that yields the same dead token is not retried`() {
        val resolver = StaleForOlderTokens()
        val repo = MovieLinkStreamRepository(
            resolverProvider = { resolver },
            seriesIdOf = { "series/gopal-bhar" },
            // The page hands the same dead token back: rebuilding the link reproduces the stored one,
            // so there is nothing new to try and a second attempt would only be a wasted request.
            playerLinkOf = { "https://movielinkbd.one/p/oldToken" },
        )

        val error = runBlocking {
            repo.resolve(animeId, episode(number = 3, link = "https://movielinkbd.one/p/oldToken?ep=3"), AudioTrack.SUB)
                .exceptionOrNull()
        }

        assertTrue(error is ResolveException.Stale)
        assertEquals(1, resolver.urls.size)
    }

    @Test
    fun `a refresh is retried exactly once, never in a loop`() {
        val resolver = StaleForOlderTokens()
        val repo = MovieLinkStreamRepository(
            resolverProvider = { resolver },
            seriesIdOf = { "series/gopal-bhar" },
            playerLinkOf = { "https://movielinkbd.one/p/oldToken" },
        )

        val error = runBlocking {
            // The stored link lost its `ep` parameter, so the rebuilt link differs and one retry runs.
            // It is still the same dead token, so the retry is the last attempt.
            repo.resolve(animeId, episode(number = 1, link = "https://movielinkbd.one/p/oldToken"), AudioTrack.SUB)
                .exceptionOrNull()
        }

        assertTrue(error is ResolveException.Stale)
        assertEquals(2, resolver.urls.size)
    }

    @Test
    fun `without a title page a stale token is reported as stale, not as a parse failure`() {
        val repo = MovieLinkStreamRepository(resolverProvider = { StaleForOlderTokens() })

        val error = runBlocking { repo.resolve(animeId, episode(), AudioTrack.SUB).exceptionOrNull() }

        assertTrue(error is ResolveException.Stale)
    }

    /** Serves one stream, but answers [ResolveException.Stale] for every link that is not the fresh one. */
    private inner class StaleForOlderTokens : StreamResolver {
        val urls = mutableListOf<String>()

        override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> {
            urls += embedUrl
            return if (embedUrl.contains("freshToken")) {
                Result.success(stream.copy(masterUrl = "https://fsl.movielink.tv/p/freshToken"))
            } else {
                Result.failure(ResolveException.Stale("embed page 400"))
            }
        }
    }

    // ------------------------------------------------------------------ failure mapping

    @Test
    fun `a resolver failure keeps its type`() {
        val blocked: Throwable = ResolveException.Blocked("403")
        val repo = MovieLinkStreamRepository(resolverProvider = { FakeResolver(Result.failure(blocked)) })

        val error = runBlocking { repo.resolve(animeId, episode(), AudioTrack.SUB).exceptionOrNull() }

        assertSame(blocked, error)
    }

    @Test
    fun `403 is blocked, 404 and 410 are not found, transport is network`() {
        val repo = MovieLinkStreamRepository(resolverProvider = { FakeResolver(Result.success(stream)) })

        assertTrue(repo.classify(MovieLinkBlockedException(403, "challenge")) is ResolveException.Blocked)
        assertTrue(repo.classify(MovieLinkHttpException(403, "HTTP 403")) is ResolveException.Blocked)
        assertTrue(repo.classify(MovieLinkHttpException(404, "HTTP 404")) is ResolveException.NotFound)
        assertTrue(repo.classify(MovieLinkHttpException(410, "HTTP 410")) is ResolveException.NotFound)
        assertTrue(repo.classify(MovieLinkHttpException(503, "HTTP 503")) is ResolveException.Network)
        assertTrue(repo.classify(IOException("connection reset")) is ResolveException.Network)
    }

    @Test
    fun `a page that changed shape is a parse failure, not a network one`() {
        val repo = MovieLinkStreamRepository(resolverProvider = { FakeResolver(Result.success(stream)) })

        assertTrue(repo.classify(IOException("not a movielink page id: hi")) is ResolveException.Parse)
        assertTrue(repo.classify(IOException("unusable path 'x'")) is ResolveException.Parse)
        assertTrue(repo.classify(IllegalStateException("no __PD2")) is ResolveException.Parse)
    }

    // ------------------------------------------------------------------ rows

    @Test
    fun `episode rows come from the provider with no dub and a deterministic id`() {
        var asked: String? = null
        val repo = MovieLinkStreamRepository(
            resolverProvider = { FakeResolver(Result.success(stream)) },
            episodesOf = { seriesId ->
                asked = seriesId
                listOf(
                    providerEpisode(2, "https://movielinkbd.one/p/tok2", title = "Ep 2"),
                    providerEpisode(1, "https://movielinkbd.one/p/tok1", title = "Ep 1"),
                )
            },
        )

        val rows = runBlocking { repo.episodes(animeId, "https://mirror.example/anime/some-title") }
            .getOrThrow()

        assertEquals("https://mirror.example/anime/some-title", asked)
        assertEquals(listOf(1, 2), rows.map { it.number })
        assertEquals(listOf("Ep 1", "Ep 2"), rows.map { it.title })
        assertTrue(rows.all { it.dubUrl == null })
        assertTrue(rows.all { it.animeId == animeId })
        assertEquals(listOf("https://movielinkbd.one/p/tok1", "https://movielinkbd.one/p/tok2"), rows.map { it.subUrl })
    }

    @Test
    fun `an empty provider list is a typed not-found`() {
        val repo = MovieLinkStreamRepository(
            resolverProvider = { FakeResolver(Result.success(stream)) },
            episodesOf = { emptyList() },
        )

        val error = runBlocking { repo.episodes(animeId, "movie/empty") }.exceptionOrNull()

        assertTrue(error is ResolveException.NotFound)
    }

    @Test
    fun `rows without a link are dropped and duplicate numbers collapse`() {
        val rows = MovieLinkEpisodes.rows(
            animeId = animeId,
            entries = listOf(
                providerEpisode(1, "https://movielinkbd.one/p/tok1"),
                providerEpisode(1, "https://movielinkbd.one/p/tok1dup"),
                providerEpisode(2, null),
                providerEpisode(3, "https://movielinkbd.one/p/tok3"),
            ),
            nowEpochSeconds = 1_700_000_000L,
        )

        assertEquals(listOf(1, 3), rows.map { it.number })
        assertEquals("https://movielinkbd.one/p/tok1", rows.first().subUrl)
        assertEquals(1_700_000_000L, rows.first().updatedAtEpoch)
    }

    @Test
    fun `the same title and number always produce the same episode id`() {
        assertEquals(
            MovieLinkIds.episodeId(animeId, 7),
            MovieLinkIds.episodeId(animeId, 7),
        )
        // Re-syncing the same page must upsert, not append: the rows are equal in every field.
        assertEquals(
            MovieLinkEpisodes.rows(animeId, listOf(providerEpisode(7, "https://movielinkbd.one/p/tok7")), 1L),
            MovieLinkEpisodes.rows(animeId, listOf(providerEpisode(7, "https://movielinkbd.one/p/tok7")), 1L),
        )
    }

    @Test
    fun `episode ids cannot land in the original or the Hindi id space`() {
        val top = MovieLinkIds.episodeId(MovieLinkIds.MAX_ID, 1)
        val bottom = MovieLinkIds.episodeId(MovieLinkIds.MIN_ID, 999)

        // The Hindi scheme at its widest is -(1_999_999_999 * 1000 + 999).
        val widestHindi = -(1_999_999_999L * 1_000L + 999L)
        assertTrue(bottom < top)
        assertTrue(top < widestHindi)
        assertTrue(bottom < widestHindi)

        // A bogus four-digit number cannot borrow another series' block.
        assertEquals(
            MovieLinkIds.episodeId(animeId, 999),
            MovieLinkIds.episodeId(animeId, 12_345),
        )
    }

    @Test
    fun `the id ranges are adjacent and do not overlap the Hindi space`() {
        assertTrue(MovieLinkIds.isMovieLink(MovieLinkIds.MIN_ID))
        assertTrue(MovieLinkIds.isMovieLink(MovieLinkIds.MAX_ID))
        assertTrue(MovieLinkIds.isMovieLink(-2_000_000_001))
        assertTrue(MovieLinkIds.isMovieLink(-2_100_000_000))

        assertTrue(!MovieLinkIds.isMovieLink(-2_100_000_001))
        assertTrue(!MovieLinkIds.isMovieLink(-2_000_000_000))
        assertTrue(!MovieLinkIds.isMovieLink(-1))
        assertTrue(!MovieLinkIds.isMovieLink(0))
    }

    // ------------------------------------------------------------------ end to end

    /**
     * The whole path with the production resolver: a token page (served by an OkHttp interceptor, so no
     * socket is opened) becomes the progressive MP4 the player is handed. This is the test that would
     * catch a repo that passes the wrong link or the wrong track to the resolver.
     */
    @Test
    fun `the production resolver turns the token page into a progressive stream`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fake")
                    .body(
                        ("<script>window.__PD2 = {\"master\":\"https://watch.movielink.tv/m/tokRepo\","
                            + "\"mp4\":\"https://fsl.movielink.tv/p/tokRepo\",\"episodes\":[]};</script>")
                            .toResponseBody("text/html; charset=utf-8".toMediaType()),
                    )
                    .build()
            }
            .build()

        val repo = MovieLinkStreamRepository(
            resolverProvider = {
                com.animo.app.player.movielink.MovieLinkStreamResolver(client, "https://movielinkbd.one/")
            },
        )

        val resolved = runBlocking {
            // A unique token: the resolver's shared cache is process-wide and must not mask the request.
            repo.resolve(animeId, episode(link = "https://movielinkbd.one/p/tokRepoE2E"), AudioTrack.SUB)
                .getOrThrow()
        }

        assertEquals("https://fsl.movielink.tv/p/tokRepo", resolved.masterUrl)
        assertEquals(StreamContainer.PROGRESSIVE, resolved.container)
        assertEquals("https://movielinkbd.one/", resolved.referer)
        assertEquals(AudioTrack.SUB, resolved.audio)
        assertNull(resolved.thumbnails)
    }

    private fun providerEpisode(number: Int, link: String?, title: String = "") = ProviderEpisode(
        providerId = "movielink",
        seriesId = "anime/some-title",
        number = number,
        title = title,
        subUrl = link,
        dubUrl = null,
        remoteId = link?.substringAfterLast('/'),
    )

    private class FakeResolver(private val result: Result<PlayableStream>) : StreamResolver {
        val urls = mutableListOf<String>()
        val audios = mutableListOf<AudioTrack>()

        override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> {
            urls += embedUrl
            audios += audio
            return result
        }
    }
}
