package com.animo.app.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder's contract: a refusal is "next rung", a hard answer is an answer, and a body that is
 * secretly a Cloudflare interstitial never reaches the caller as content.
 */
class TransportRouterTest {

    private class Fake(
        override val name: String,
        private val answer: (String) -> ChannelOutcome,
    ) : TransportChannel {

        var calls = 0
        var posts: Int = 0
        var lastUrl: String? = null

        override suspend fun execute(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): ChannelOutcome {
            calls++
            lastUrl = url
            return answer(url)
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): ChannelOutcome {
            posts++
            calls++
            return answer(url)
        }
    }

    private fun response(channel: String, code: Int = 200, body: String = "{}") =
        TransportResponse(code = code, body = body, headers = emptyMap(), channel = channel)

    private fun ok(channel: String, code: Int = 200, body: String = "{}") =
        ChannelOutcome.Ok(response(channel, code, body))

    // ---------------------------------------------------------------- walking the ladder

    @Test
    fun `a refused first channel hands over to the second`() = runBlocking {
        val first = Fake("desktop") { ChannelOutcome.Refused("http 403") }
        val second = Fake("browser") { ok("browser", body = "payload") }
        val router = TransportRouter(listOf(first, second))

        val result = router.get("https://one.invalid/page")

        assertTrue(result.isSuccess)
        assertEquals("payload", result.getOrNull()?.body)
        assertEquals("browser", result.getOrNull()?.channel)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun `an empty ladder is a typed failure with no attempts`() = runBlocking {
        val result = TransportRouter(emptyList()).get("https://one.invalid/page")

        val error = result.exceptionOrNull()
        assertTrue(error is TransportBlockedException)
        assertTrue((error as TransportBlockedException).attempts.isEmpty())
        assertEquals("one.invalid", error.host)
        assertFalse(error.refused)
    }

    @Test
    fun `every rung refusing is blocked, not dead`() = runBlocking {
        val first = Fake("desktop") { ChannelOutcome.Refused("http 403") }
        val second = Fake("browser") { ChannelOutcome.Refused("http 429") }
        val router = TransportRouter(listOf(first, second))

        val error = router.get("https://two.invalid/page").exceptionOrNull() as TransportBlockedException

        assertEquals(listOf("desktop", "browser"), error.attempts.map { it.channel })
        assertEquals(listOf(AttemptStatus.REFUSED, AttemptStatus.REFUSED), error.attempts.map { it.status })
        assertTrue(error.refused)
        // The cooldown is armed for the *next* call, and the caller can read it without the budget.
        assertTrue(error.retryAfterMs > 0L)
    }

    @Test
    fun `a failed channel hands over without charging the host a cooldown`() = runBlocking {
        val budget = HostBudget(minIntervalMs = 0L, maxBackoffMs = 30_000L)
        val first = Fake("desktop") { ChannelOutcome.Failed(IOException("dns")) }
        val second = Fake("browser") { ok("browser") }
        val router = TransportRouter(listOf(first, second), budget)

        assertTrue(router.get("https://three.invalid/page").isSuccess)
        assertEquals(0, budget.strikes("three.invalid"))
    }

    @Test
    fun `all channels failing keeps the transport error as the cause`() = runBlocking {
        val boom = IOException("socket closed")
        val first = Fake("desktop") { ChannelOutcome.Failed(boom) }
        val second = Fake("browser") { ChannelOutcome.Failed(IOException("tls")) }
        val router = TransportRouter(listOf(first, second))

        val error = router.get("https://four.invalid/page").exceptionOrNull() as TransportBlockedException

        assertEquals(boom, error.cause)
        assertFalse(error.refused)
        assertEquals(listOf(AttemptStatus.FAILED, AttemptStatus.FAILED), error.attempts.map { it.status })
    }

    // ---------------------------------------------------------------- what counts as a refusal

    @Test
    fun `a challenge page served with 200 is refused`() = runBlocking {
        val first = Fake("desktop") {
            ok("desktop", body = "<html><title>Just a moment...</title><div id=\"cf_chl\"></div>")
        }
        val second = Fake("browser") { ok("browser", body = "real") }
        val router = TransportRouter(listOf(first, second))

        val result = router.get("https://five.invalid/page")

        assertEquals("real", result.getOrNull()?.body)
        assertEquals(1, second.calls)
    }

    @Test
    fun `a 404 is an answer and stops the ladder`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", code = 404, body = "gone") }
        val second = Fake("browser") { ok("browser") }
        val router = TransportRouter(listOf(first, second))

        val result = router.get("https://six.invalid/page")

        assertEquals(404, result.getOrNull()?.code)
        assertEquals(0, second.calls)
    }

    @Test
    fun `a 410 is an answer and stops the ladder`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", code = 410, body = "dead") }
        val second = Fake("browser") { ok("browser") }
        val router = TransportRouter(listOf(first, second))

        assertEquals(410, router.get("https://seven.invalid/page").getOrNull()?.code)
        assertEquals(0, second.calls)
    }

    @Test
    fun `a channel that hands over a bare 403 is still treated as a refusal`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", code = 403, body = "Forbidden") }
        val second = Fake("browser") { ok("browser", body = "real") }
        val router = TransportRouter(listOf(first, second))

        assertEquals("real", router.get("https://eight.invalid/page").getOrNull()?.body)
        assertEquals(1, second.calls)
    }

    @Test
    fun `a 429 is a refusal`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", code = 429) }
        val second = Fake("browser") { ok("browser", body = "real") }
        val router = TransportRouter(listOf(first, second))

        assertEquals("real", router.get("https://nine.invalid/page").getOrNull()?.body)
    }

    @Test
    fun `a body that merely mentions a marker past the head is not a challenge`() = runBlocking {
        val body = "x".repeat(9 * 1024) + "challenge-platform"
        val first = Fake("desktop") { ok("desktop", body = body) }
        val second = Fake("browser") { ok("browser") }
        val router = TransportRouter(listOf(first, second))

        assertEquals(200, router.get("https://ten.invalid/page").getOrNull()?.code)
        assertEquals(0, second.calls)
    }

    // ---------------------------------------------------------------- inputs and plumbing

    @Test
    fun `a blank url never reaches a channel`() = runBlocking {
        val first = Fake("desktop") { ok("desktop") }
        val router = TransportRouter(listOf(first))

        val result = router.get("   ")

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, first.calls)
    }

    @Test
    fun `post goes through the channel's post path`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", body = "created") }
        val router = TransportRouter(listOf(first))

        val result = router.post("https://eleven.invalid/page", body = "a=1")

        assertEquals("created", result.getOrNull()?.body)
        assertEquals(1, first.posts)
        assertEquals(0, first.calls - first.posts)
    }

    @Test
    fun `log lines name slots, never the request or the response body`() = runBlocking {
        val lines = mutableListOf<String>()
        val secret = "token=abc123secret"
        val first = Fake("desktop") { ChannelOutcome.Refused("http 403") }
        val second = Fake("browser") { ok("browser", body = secret) }
        val router = TransportRouter(listOf(first, second), log = { lines += it })

        router.get("https://twelve.invalid/page?$secret")

        assertTrue(lines.any { it.contains("desktop") })
        assertTrue(lines.any { it.contains("browser") })
        assertFalse(lines.any { it.contains("abc123secret") })
        assertNotNull(lines.firstOrNull())
    }
}
