package com.animo.app.player.movielink

import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.player.ResolveException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole HTTP layer is an OkHttp interceptor, so every hop is exercised offline: no socket, no
 * MockWebServer dependency, and the assertions can also look at the exact request headers.
 *
 * Resolved streams go into the process-wide [com.animo.app.player.ResolveCache], so every test uses
 * its own token — a shared cache hit would otherwise skip the request under test.
 */
class MovieLinkStreamResolverTest {

    @Test
    fun `a movie shell resolves to the progressive mp4 with the watch referer`() {
        val site = FakeSite(200, SHELL_MOVIE)
        val resolver = MovieLinkStreamResolver(site.client(), REFERER)

        val stream = resolve(resolver, "https://movielinkbd.one/p/tokMovieSub")
        assertEquals("https://fsl.movielink.tv/p/tokMovieSub", stream.masterUrl)
        assertEquals(StreamContainer.PROGRESSIVE, stream.container)
        assertEquals(REFERER, stream.referer)
        assertEquals(HttpClientFactory.UA_DESKTOP, stream.userAgent)
        assertEquals(AudioTrack.SUB, stream.audio)

        // The page fetch itself has to look like the browser the site expects.
        assertEquals(1, site.requests)
        assertEquals(REFERER, site.last?.header("Referer"))
        assertEquals(HttpClientFactory.UA_DESKTOP, site.last?.header("User-Agent"))
        assertNull(site.last?.header("X-Requested-With"))
    }

    @Test
    fun `a series shell can be pointed at one episode with the ep parameter`() {
        val resolver = MovieLinkStreamResolver(FakeSite(200, SHELL_SERIES).client(), REFERER)

        assertEquals("https://fsl.movielink.tv/p/tokE1", resolve(resolver, "https://movielinkbd.one/p/tokSeriesOne").masterUrl)
        assertEquals("https://fsl.movielink.tv/p/tokE2", resolve(resolver, "https://movielinkbd.one/p/tokSeriesTwo?ep=2").masterUrl)
        assertEquals("https://fsl.movielink.tv/p/tokE3", resolve(resolver, "https://movielinkbd.one/p/tokSeriesThree?ep=3").masterUrl)
    }

    @Test
    fun `an entry with only a master resolves as HLS`() {
        val resolver = MovieLinkStreamResolver(FakeSite(200, SHELL_MASTER_ONLY).client(), REFERER)

        val stream = resolve(resolver, "https://movielinkbd.one/p/tokHlsOnly")
        assertEquals("https://watch.movielink.tv/m/tokHlsOnly", stream.masterUrl)
        assertEquals(StreamContainer.HLS, stream.container)
    }

    @Test
    fun `403 is blocked while 404 and 410 are definitive`() {
        val blocked = failureOf(FakeSite(403, CLOUDFLARE_CHALLENGE), "https://movielinkbd.one/p/tok403")
        assertTrue(blocked is ResolveException.Blocked)

        val missing = failureOf(FakeSite(404, "not found"), "https://movielinkbd.one/p/tok404")
        assertTrue(missing is ResolveException.NotFound)

        val dead = failureOf(FakeSite(410, "gone"), "https://movielinkbd.one/p/tok410")
        assertTrue(dead is ResolveException.Dead)
    }

    @Test
    fun `a page without the config is a parse error and a dropped socket is a network error`() {
        val parse = failureOf(FakeSite(200, "<html>no player here</html>"), "https://movielinkbd.one/p/tokNoPd2")
        assertTrue(parse is ResolveException.Parse)

        val network = failureOf(FakeSite(0, "", IOException("connection reset")), "https://movielinkbd.one/p/tokIo")
        assertTrue(network is ResolveException.Network)
    }

    private fun resolve(resolver: MovieLinkStreamResolver, url: String): PlayableStream =
        runBlocking { resolver.resolve(url, AudioTrack.SUB).getOrThrow() }

    private fun failureOf(site: FakeSite, url: String): Throwable? =
        runBlocking { MovieLinkStreamResolver(site.client(), REFERER).resolve(url, AudioTrack.SUB).exceptionOrNull() }

    /** Returns synthetic responses without touching the network. `status = 0` throws [failure]. */
    private class FakeSite(private val status: Int, private val body: String, private val failure: IOException? = null) {

        var requests: Int = 0
            private set
        var last: Request? = null
            private set

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests++
                last = chain.request()
                failure?.let { throw it }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fake")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
    }

    private companion object {
        const val REFERER = "https://movielinkbd.one/"
        const val CLOUDFLARE_CHALLENGE =
            "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment...</title></head></html>"

        val SHELL_MOVIE = """
            <script>window.__PD2 = {"master":"https://watch.movielink.tv/m/tokMovieSub","mp4":"https://fsl.movielink.tv/p/tokMovieSub","episodes":[{"label":"Movie","master":"https://watch.movielink.tv/m/tokMovieSub","mp4":"https://fsl.movielink.tv/p/tokMovieSub"}]};</script>
        """.trimIndent()

        val SHELL_SERIES = """
            <script>window.__PD2 = {"master":"https://watch.movielink.tv/m/tokE1","mp4":"https://fsl.movielink.tv/p/tokE1","episodes":[
              {"label":"EP 1","master":"https://watch.movielink.tv/m/tokE1","mp4":"https://fsl.movielink.tv/p/tokE1"},
              {"label":"EP 2","master":"https://watch.movielink.tv/m/tokE2","mp4":"https://fsl.movielink.tv/p/tokE2"},
              {"label":"EP 3","master":"https://watch.movielink.tv/m/tokE3","mp4":"https://fsl.movielink.tv/p/tokE3"}
            ]};</script>
        """.trimIndent()

        val SHELL_MASTER_ONLY = """
            <script>window.__PD2 = {"master":"https://watch.movielink.tv/m/tokHlsOnly","episodes":[{"label":"EP 1","master":"https://watch.movielink.tv/m/tokHlsOnly"}]};</script>
        """.trimIndent()
    }
}
