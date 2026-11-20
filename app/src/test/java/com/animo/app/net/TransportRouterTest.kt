package com.animo.app.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override suspend fun execute(
            url: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): ChannelOutcome {
            calls++
            return answer(url)
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            timeoutMs: Long,
        ): ChannelOutcome {
            posts++
            return answer(url)
        }
    }

    private fun ok(channel: String, code: Int = 200, body: String = "{}") = ChannelOutcome.Ok(
        TransportResponse(code = code, body = body, headers = emptyMap(), channel = channel),
    )

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
    fun `every rung refusing is blocked, not dead, and the log names slots only`() = runBlocking {
        val lines = mutableListOf<String>()
        val first = Fake("desktop") { ChannelOutcome.Refused("http 403") }
        val second = Fake("browser") { ChannelOutcome.Refused("http 429") }
        val router = TransportRouter(listOf(first, second), log = { lines += it })

        val error = router.get("https://two.invalid/page?token=abc123secret")
            .exceptionOrNull() as TransportBlockedException

        assertEquals(listOf("desktop", "browser"), error.attempts.map { it.channel })
        assertEquals(listOf(AttemptStatus.REFUSED, AttemptStatus.REFUSED), error.attempts.map { it.status })
        assertTrue(error.refused)
        // The cooldown is armed for the *next* call, and the caller can read it without the budget.
        assertTrue(error.retryAfterMs > 0L)
        assertEquals("two.invalid", error.host)
        assertTrue(lines.any { it.contains("desktop") })
        assertFalse(lines.any { it.contains("abc123secret") })
    }

    @Test
    fun `a challenge body with 200 and a bare 403 or 429 are all refusals`() = runBlocking {
        val refusals = listOf(
            ok("desktop", code = 200, body = "<html><title>Just a moment...</title><div id=\"cf_chl\"></div>"),
            ok("desktop", code = 403, body = "Forbidden"),
            ok("desktop", code = 429, body = ""),
        )

        refusals.forEach { refusal ->
            val first = Fake("desktop") { refusal }
            val second = Fake("browser") { ok("browser", body = "real") }
            val result = TransportRouter(listOf(first, second)).get("https://three.invalid/page")

            assertEquals("real", result.getOrNull()?.body)
            assertEquals(1, second.calls)
        }
    }

    @Test
    fun `404 and 410 are answers and stop the ladder`() = runBlocking {
        listOf(404, 410).forEach { code ->
            val first = Fake("desktop") { ok("desktop", code = code, body = "gone") }
            val second = Fake("browser") { ok("browser") }
            val result = TransportRouter(listOf(first, second)).get("https://four.invalid/page")

            assertEquals(code, result.getOrNull()?.code)
            assertEquals(0, second.calls)
        }
    }

    @Test
    fun `failures move on without a cooldown and a fully failed ladder keeps the cause`() = runBlocking {
        val budget = HostBudget(minIntervalMs = 0L, maxBackoffMs = 30_000L)
        val boom = IOException("socket closed")
        val slow = Fake("desktop") { ChannelOutcome.Failed(boom) }
        val fast = Fake("browser") { ok("browser") }

        assertTrue(TransportRouter(listOf(slow, fast), budget).get("https://five.invalid/page").isSuccess)
        // A socket error is not the host objecting to us, so nothing is charged to it.
        assertEquals(0, budget.strikes("five.invalid"))

        val allFailed = TransportRouter(
            listOf(slow, Fake("browser") { ChannelOutcome.Failed(IOException("tls")) }),
        ).get("https://five.invalid/page").exceptionOrNull() as TransportBlockedException

        assertEquals(boom, allFailed.cause)
        assertFalse(allFailed.refused)
        assertEquals(listOf(AttemptStatus.FAILED, AttemptStatus.FAILED), allFailed.attempts.map { it.status })
    }

    @Test
    fun `a blank url never reaches a channel and an empty ladder is a typed failure`() = runBlocking {
        val first = Fake("desktop") { ok("desktop") }
        val blank = TransportRouter(listOf(first)).get("   ")
        assertTrue(blank.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, first.calls)

        val empty = TransportRouter(emptyList()).get("https://six.invalid/page")
            .exceptionOrNull() as TransportBlockedException
        assertTrue(empty.attempts.isEmpty())
        assertFalse(empty.refused)
    }

    @Test
    fun `post goes through the channel's post path`() = runBlocking {
        val first = Fake("desktop") { ok("desktop", body = "created") }
        val router = TransportRouter(listOf(first))

        val result = router.post("https://seven.invalid/page", body = "a=1")

        assertEquals("created", result.getOrNull()?.body)
        assertEquals(1, first.posts)
        assertEquals(0, first.calls)
    }
}
