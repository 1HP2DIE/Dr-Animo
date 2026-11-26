package com.animo.app.data.remote.provider.movielink

import com.animo.app.player.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block heuristic, which is the one mistake here that is worse than the block itself.
 *
 * On 2026-11-26 the index began embedding Cloudflare's JS-detection bootstrap
 * (`/cdn-cgi/challenge-platform/scripts/jsd/main.js`) in **normal** pages. That snippet contains the
 * marker the provider looked for, so every 200 was read as a challenge and the whole second
 * catalogue silently stopped answering — searches empty, episode lists empty, syncs writing nothing.
 *
 * A real page is ~535 KB; a Cloudflare interstitial is ~6 KB. These two facts pin the fix: the size
 * decides, and the marker only applies below it.
 */
class MovieLinkProviderChallengeTest {

    private val provider = MovieLinkProvider(
        client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fake")
                .body(page.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build(),
        indexHost = "https://mirror.example",
        resolverProvider = { NoopResolver },
        minIntervalMs = 0,
        playerTokenTtlMs = 0,
    )

    private var page: String = ""

    @Test
    fun `a real page that carries the cloudflare snippet is not a block`() {
        // The measured shape: the marker appears inside the page's own injected bootstrap, while the
        // page around it is a full title page with a player frame.
        page = buildString {
            append("<html><head><title>Gopal Bhar</title></head><body>")
            append("<div>").append("x".repeat(520_000)).append("</div>")
            append("<iframe id=\"mlbdHdFrame\" src=\"/p/liveToken1234567890\"></iframe>")
            append("<script>var u='/cdn-cgi/challenge-platform/scripts/jsd/main.js';</script>")
            append("</body></html>")
        }

        val detail = runBlocking { provider.detail("series/gopal-bhar") }.getOrThrow()

        assertEquals("/p/liveToken1234567890", detail.playerUrl)
        assertTrue(detail.playerUrl.isNotEmpty())
    }

    @Test
    fun `a small interstitial is still a block`() {
        page = """
            <!DOCTYPE html><html><head><title>Just a moment...</title></head>
            <body><div id="cf-challenge-running"></div>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script></body></html>
        """.trimIndent()

        val error = runBlocking { provider.detail("series/gopal-bhar") }.exceptionOrNull()

        assertTrue("expected Blocked, got $error", error is MovieLinkBlockedException)
    }

    @Test
    fun `a page with no player still parses instead of looking blocked`() {
        page = "<html><body><h1>Nothing here</h1></body></html>"

        val detail = runBlocking { provider.detail("series/gopal-bhar") }.getOrThrow()

        assertNotNull(detail)
        assertEquals("", detail.playerUrl)
    }

    /** Never used by these tests: they only read detail pages. */
    private object NoopResolver : StreamResolver {
        override suspend fun resolve(
            embedUrl: String,

            audio: com.animo.app.domain.model.AudioTrack,
        ): Result<com.animo.app.domain.model.PlayableStream> =
            Result.failure(UnsupportedOperationException("not used"))
    }
}
