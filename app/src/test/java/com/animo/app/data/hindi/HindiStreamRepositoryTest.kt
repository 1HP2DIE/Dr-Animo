package com.animo.app.data.hindi

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.player.ResolveException
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Offline tests for the Hindi resolver.
 *
 * No network and no fixtures beyond the ones already in the repo: the requirejs chain
 * (episode page -> `trembed` -> argon embed) is served by an OkHttp **application** interceptor, which
 * runs before any socket is opened. The embed page is the real captured
 * `src/test/resources/juicy/embed.html`, so the decode path under test is the live one —
 * [JuicyDecoderTest] independently pins its master URL to the one the host's own `player.js` produced.
 *
 * The providers are the production classes, not stubs: [ToonoProvider.embedId] and
 * [RareAnimesProvider.embedUrl] are exercised for real against the intercepted pages.
 */
class HindiStreamRepositoryTest {

    /**
     * The endpoints live in the encrypted vault at runtime; unit tests have no assets, so the same
     * map is injected. The hosts are the ones the captured fixtures were recorded against.
     */
    @Before
    fun installVault() {
        ProviderVault.installForTests(
            mapOf(
                ProviderKey.DUB_HOST_A.slot to "https://toono.app",
                ProviderKey.DUB_HOST_B.slot to "https://www.rareanimes.foo",
                ProviderKey.DUB_HOST_B_REFERER.slot to "https://www.rareanimes.foo/",
                ProviderKey.DUB_CDN.slot to "https://argon.razorshell.space",
                ProviderKey.DUB_GATE_HOST.slot to "multiquality.click",
                ProviderKey.DUB_GATE_PATH.slot to "/multiquality",
                ProviderKey.DUB_EMBED_MARKER.slot to "multiquality",
                ProviderKey.DUB_WATCH_MARKER.slot to "WatchMultiQuality",
                ProviderKey.DUB_SLUG_PREFIX_A.slot to "toono-",
                ProviderKey.DUB_SLUG_PREFIX_B.slot to "rare-",
            ),
        )
    }

    // ------------------------------------------------------------------ toono

    @Test
    fun `a toono episode resolves to the argon master playlist`() = runBlocking {
        val fake = FakeHost()
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        val stream = result.getOrThrow()
        assertEquals(MASTER_URL, stream.masterUrl)
        assertEquals("https://argon.razorshell.space/", stream.referer)
        // The embed page only serves the payload to a UA the origin does not reject, and it has to
        // be the same string the warm-up WebView sends, or the clearance cannot be replayed.
        // Pinned to the shared constant rather than a literal: the old literal was a headless-Chrome
        // string, which the origin answers with a bare 403 on uncached paths.
        assertEquals(
            com.animo.app.data.remote.HttpClientFactory.UA_DESKTOP,
            stream.userAgent,
        )
        assertNull(stream.thumbnails)
        assertNull(stream.intro)
        assertNull(stream.outro)
        // The embed chain really ran: episode page, trembed hop, embed page.
        assertEquals(1, fake.requests.count { it.contains("/episode/") })
        assertEquals(1, fake.requests.count { it.contains("trembed=1") })
        assertEquals(1, fake.requests.count { it.contains("/embed/") })
    }

    @Test
    fun `the returned audio is always the dub, even when the caller asks for sub`() = runBlocking {
        val fake = FakeHost()
        val stream = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.SUB).getOrThrow()

        assertEquals(AudioTrack.DUB, stream.audio)
        assertEquals(MASTER_URL, stream.masterUrl)
    }

    @Test
    fun `a second resolve of the same episode does not touch the network`() = runBlocking {
        val fake = FakeHost()
        val repository = repository(fake)

        val first = repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()
        val before = fake.requests.size
        val second = repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()

        assertEquals(before, fake.requests.size)
        assertEquals(first.masterUrl, second.masterUrl)
    }

    @Test
    fun `a changed source url invalidates the cached entry`() = runBlocking {
        val fake = FakeHost()
        val repository = repository(fake)

        repository.resolve(TOONO_ID, toonoEpisode(url = "$TOONO_BASE/episode/1-happy-family-usa-1x1/"), AudioTrack.DUB)
            .getOrThrow()
        val afterFirst = fake.requests.size

        // Same episode id, re-synced row: the argon id behind it may be different, so the cached
        // manifest must not be reused.
        repository.resolve(TOONO_ID, toonoEpisode(url = "$TOONO_BASE/episode/1-happy-family-usa-1x2/"), AudioTrack.DUB)
            .getOrThrow()

        assertEquals(afterFirst + 3, fake.requests.size)
    }

    @Test
    fun `invalidate drops the entry so the next resolve refetches`() = runBlocking {
        val fake = FakeHost()
        val repository = repository(fake)

        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()
        val afterFirst = fake.requests.size

        repository.invalidate(toonoEpisode().id)
        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()

        assertEquals(afterFirst + 3, fake.requests.size)
    }

    @Test
    fun `a ttl of zero turns every resolve into a refetch`() = runBlocking {
        val fake = FakeHost()
        var now = 0L
        val repository = repository(fake, ttlMs = 0L, clock = { now })

        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()
        val afterFirst = fake.requests.size
        now = 1L
        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()

        assertEquals(afterFirst + 3, fake.requests.size)
    }

    // ------------------------------------------------------------------ rareanimes

    @Test
    fun `a rareanimes embed link resolves without a provider hop`() = runBlocking {
        val fake = FakeHost()
        val episode = Episode(
            id = 42L,
            animeId = RARE_ID,
            number = 1,
            subUrl = "https://argon.razorshell.space/embed/U2vgc4LiRSp0lBM",
        )

        val stream = repository(fake).resolve(RARE_ID, episode, AudioTrack.DUB).getOrThrow()

        assertEquals(MASTER_URL, stream.masterUrl)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `a rareanimes downlead link is rewritten into the argon embed`() = runBlocking {
        val fake = FakeHost()
        val episode = Episode(
            id = 43L,
            animeId = RARE_ID,
            number = 1,
            subUrl = "https://codedew.com/downlead/U2vgc4LiRSp0lBM",
        )

        val stream = repository(fake).resolve(RARE_ID, episode, AudioTrack.DUB).getOrThrow()

        assertEquals(MASTER_URL, stream.masterUrl)
        assertTrue(fake.requests.single().startsWith("https://argon.razorshell.space/embed/"))
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun `a positive anime id is rejected without touching the network`() = runBlocking {
        val fake = FakeHost()
        val result = repository(fake).resolve(1234, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("not a Hindi id: 1234 (expected -1999999999..-1)", result.exceptionOrNull()?.message)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `an id outside both Hindi ranges is rejected`() = runBlocking {
        val fake = FakeHost()
        val result = repository(fake).resolve(-2_000_000_000, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a blank source url fails with NotFound`() = runBlocking {
        val fake = FakeHost()
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(url = "   "), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.NotFound)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a missing source url fails with NotFound`() = runBlocking {
        val fake = FakeHost()
        val episode = Episode(id = 7L, animeId = TOONO_ID, number = 1, subUrl = null)

        assertTrue(repository(fake).resolve(TOONO_ID, episode, AudioTrack.DUB).exceptionOrNull() is ResolveException.NotFound)
    }

    @Test
    fun `an episode page without a player frame maps to Parse`() = runBlocking {
        val fake = FakeHost(episodePage = "<html><body>no player here</body></html>")
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.Parse)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no player frame"))
    }

    @Test
    fun `an embed that is not an argon player maps to NotFound`() = runBlocking {
        val fake = FakeHost()
        val episode = Episode(
            id = 44L,
            animeId = RARE_ID,
            number = 1,
            subUrl = "https://hubcloud.foo/drive/abc123",
        )
        val result = repository(fake).resolve(RARE_ID, episode, AudioTrack.DUB)

        // RareEmbedUnavailableException -> NotFound: a file host will never produce a stream.
        assertTrue(result.exceptionOrNull() is ResolveException.NotFound)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a 404 from the embed host maps to NotFound`() = runBlocking {
        val fake = FakeHost(embedStatus = 404)
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.NotFound)
        // 404 is definitive, so it is not retried.
        assertEquals(1, fake.requests.count { it.contains("argon.razorshell.space/embed/") })
    }

    @Test
    fun `a 403 from the embed host maps to Blocked`() = runBlocking {
        val fake = FakeHost(embedStatus = 403)
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.Blocked)
        assertEquals(1, fake.requests.count { it.contains("argon.razorshell.space/embed/") })
    }

    @Test
    fun `a transient 503 is retried before it is reported`() = runBlocking {
        val fake = FakeHost(embedStatuses = listOf(503, 200))
        val stream = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()

        assertEquals(MASTER_URL, stream.masterUrl)
        assertEquals(2, fake.requests.count { it.contains("argon.razorshell.space/embed/") })
    }

    @Test
    fun `a persistent 503 maps to Network`() = runBlocking {
        val fake = FakeHost(embedStatus = 503)
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.Network)
        assertEquals(2, fake.requests.count { it.contains("argon.razorshell.space/embed/") })
    }

    @Test
    fun `an embed page without a juicycodes call maps to Parse`() = runBlocking {
        val fake = FakeHost(embedHtml = "<html><body>player moved</body></html>")
        val result = repository(fake).resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(result.exceptionOrNull() is ResolveException.Parse)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no _juicycodes"))
    }

    // ------------------------------------------------------------------ trace

    @Test
    fun `describe reports the hops of the last resolve`() = runBlocking {
        val fake = FakeHost()
        val repository = repository(fake)
        assertEquals("no Hindi resolution has run yet", repository.describe())

        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB).getOrThrow()

        val description = repository.describe()
        assertTrue(description.contains("source A id U2vgc4LiRSp0lBM"))
        assertTrue(description.contains("embed #1 200"))
        assertTrue(description.contains("master "))
        assertNotNull(repository.resolveTrace().firstOrNull())
    }

    @Test
    fun `a failure is described too, so a bug report carries the hop trail`() = runBlocking {
        val fake = FakeHost(embedStatus = 404)
        val repository = repository(fake)

        repository.resolve(TOONO_ID, toonoEpisode(), AudioTrack.DUB)

        assertTrue(repository.describe().contains("failed NotFound"))
    }

    // ------------------------------------------------------------------ live check (hand-run)

    /**
     * End-to-end against the real hosts, for the report. Ignored so the suite stays offline; run it by
     * clearing the annotation (or with a temporary copy) and reading `LIVE …` from the test output.
     *
     * It asserts the decoded master equals the node-`vm` ground truth, which proves the whole
     * page -> trembed -> argon -> juicy.codes chain and not just the decoder.
     */
    @Ignore("live network: hand-run only, the suite must stay offline")
    @Test
    fun `live toono episode resolves to the fixture master url`() = runBlocking {
        val client = OkHttpClient()
        val repository = HindiStreamRepositoryImpl(
            http = client,
            toono = ToonoProvider(client),
            rareAnimes = RareAnimesProvider(
                http = client,
                catalogIntervalMs = 0L,
                resolveIntervalMs = 0L,
            ),
        )
        val episode = Episode(
            id = 9001L,
            animeId = TOONO_ID,
            number = 1,
            subUrl = "$TOONO_BASE/episode/1-happy-family-usa-1x1/",
        )

        val result = repository.resolve(TOONO_ID, episode, AudioTrack.DUB)
        println("LIVE describe: ${repository.describe()}")
        val stream = result.getOrThrow()
        println("LIVE master: ${stream.masterUrl}")
        assertEquals(MASTER_URL, stream.masterUrl)
    }

    // ------------------------------------------------------------------ helpers

    private fun repository(
        fake: FakeHost,
        ttlMs: Long = 15 * 60 * 1000L,
        clock: () -> Long = { System.currentTimeMillis() },
    ): HindiStreamRepositoryImpl {
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        return HindiStreamRepositoryImpl(
            http = client,
            toono = ToonoProvider(client),
            rareAnimes = RareAnimesProvider(
                http = client,
                catalogIntervalMs = 0L,
                resolveIntervalMs = 0L,
                maxAttempts = 1,
            ),
            ttlMs = ttlMs,
            clock = clock,
        )
    }

    private fun toonoEpisode(url: String = "$TOONO_BASE/episode/1-happy-family-usa-1x1/"): Episode =
        Episode(id = 9001L, animeId = TOONO_ID, number = 1, subUrl = url)

    /**
     * Serves the captured chain with no socket involved. Any request that is not part of the chain is
     * a test bug, so it answers 404 and the test will notice.
     */
    private class FakeHost(
        private val episodePage: String = EPISODE_PAGE,
        private val embedHtml: String? = null,
        private val embedStatus: Int = 200,
        private val embedStatuses: List<Int> = emptyList(),
    ) : Interceptor {

        val requests = CopyOnWriteArrayList<String>()

        private var embedCalls = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            requests += url.toString()

            val body = when {
                url.host == "toono.app" && url.encodedPath.startsWith("/episode/") -> episodePage
                url.host == "toono.app" && url.queryParameter("trembed") != null -> TREMBED_PAGE
                url.host == "argon.razorshell.space" && url.encodedPath.startsWith("/embed/") -> {
                    embedCalls += 1
                    val status = embedStatuses.getOrNull(embedCalls - 1) ?: embedStatus
                    return response(chain, status, if (status == 200) argonPage() else "")
                }
                else -> return response(chain, 404, "")
            }
            return response(chain, 200, body)
        }

        private fun argonPage(): String = embedHtml ?: resource("/juicy/embed.html")

        private fun response(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "status $code")
            .body(body.toResponseBody(HTML))
            .build()

        companion object {
            private val HTML = "text/html; charset=utf-8".toMediaType()

            fun resource(name: String): String =
                FakeHost::class.java.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
                    ?: error("missing test resource $name")
        }
    }

    private companion object {
        const val TOONO_BASE = "https://toono.app"

        /** Inside the toono range of the negative id space. */
        const val TOONO_ID = -42

        /** Inside the rareanimes range. */
        const val RARE_ID = -1_500_000_000

        /**
         * Captured independently by running the host's own `player.js` in a node `vm` (see
         * `JuicyDecoderTest`), so it is ground truth for the whole chain on both sides.
         */
        const val MASTER_URL =
            "https://wfbrcpgcgcwjrqh.groovy.monster/stream/AGZ5ZTH4LmSzZJRjZ2Z3MGAzLJExLGx1MwqxLmAyLmVkATZkLGL5BTExZ2VkBQL0BQAvLJRjZwuuAwV4L2D2ZP5JryO2Z1qwo3MJp2cipxAmMJ5uoaMOYxqXEQqmJH15AaSnBSEapKt2ASO6HxM1rv1EEGIApH9cp3q6A0HmImMnGaqDZQuUYHyFHGOjMaIDA1SlrxcsAGx/AzDlATV5ATVkZTMvBQL1AmIxAGxjAQV1BQMzMQH2Z2Z1ZTHmZGDjAmuxMwD2AJExZmZkAzMwAGN5BTH1LwxmAF56JTqmFxHjF2SEBQqXM3RmrHWdqzkEYaOMBRqxIF1yD0WznQWcDmWFZTuSJycbGR5JDz8kDxSyDyWTIKOFFTq0HQRkFJuKMUb1YGx4oT9KAmV1qHAGqGV.m3u8"

        /** Shape measured on `1-happy-family-usa-1x1` (see ToonoProvider.embedId). */
        const val EPISODE_PAGE =
            "<html><body><div class=\"Video\"><iframe id=\"main-player\" " +
                "src=\"https://toono.app/?trembed=1&amp;trid=39519&amp;trtype=2\" " +
                "frameborder=\"0\" allowfullscreen></iframe></div></body></html>"

        /** The real `trembed` answer (`/tmp/opencode/toono_embed_noref.txt`, 620 B). */
        const val TREMBED_PAGE =
            "<html><body oncontextmenu=\"return false;\"><div class=\"Video\">" +
                "<iframe width=\"560\" height=\"315\" " +
                "src=\"https://argon.razorshell.space/embed/U2vgc4LiRSp0lBM\" " +
                "frameborder=\"0\" allowfullscreen></iframe></div></body></html>"
    }
}
