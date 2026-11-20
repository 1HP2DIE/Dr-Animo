package com.animo.app.net.webview

import com.animo.app.net.ChannelOutcome
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The decision flow of the last rung, with the WebView replaced by a fake.
 *
 * Everything here runs on the JVM: no Robolectric, no android.jar, no device. What cannot be
 * covered this way — that a real WebView actually solves a real challenge, and that the cookie
 * jar is written — is device-only by construction.
 */
class WebViewSessionTest {

    private class FakeWarmup(
        private val cookies: FakeCookies,
        var result: WarmupResult = WarmupResult.Solved("cf_clearance=fresh"),
    ) : BrowserWarmup {
        var calls = 0
        var lastTimeoutMs = 0L
        var failure: Throwable? = null

        override suspend fun warm(url: String, timeoutMs: Long): WarmupResult {
            calls++
            lastTimeoutMs = timeoutMs
            failure?.let { throw it }
            val current = result
            if (current is WarmupResult.Solved) cookies.header = current.cookies
            return current
        }
    }

    private class FakeCookies(var header: String? = null) : CookieStore {
        val clearedHosts = mutableListOf<String>()
        var clearedAll = 0
        var flushes = 0

        override fun cookieHeader(url: String): String? = header

        override fun clearHost(host: String) {
            clearedHosts += host
            header = null
        }

        override fun clearAll() {
            clearedAll++
            header = null
        }

        override fun flush() {
            flushes++
        }
    }

    private class FakeReplay(private vararg val results: ReplayOutcome) : WebReplay {
        val cookieHeaders = mutableListOf<String?>()
        var calls = 0

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            cookieHeader: String?,
            timeoutMs: Long,
        ): ReplayOutcome {
            calls++
            cookieHeaders += cookieHeader
            if (results.isEmpty()) return ReplayOutcome.Response(200, "ok", emptyMap())
            return results[minOf(calls - 1, results.size - 1)]
        }
    }

    private val warmHost = "https://warm.test/page"

    private fun session(
        warmup: BrowserWarmup,
        cookies: CookieStore,
        replay: WebReplay,
        clock: () -> Long = { 0L },
    ) = WebViewSession(
        warmup = warmup,
        cookies = cookies,
        replay = replay,
        warmTtlMs = 1_000L,
        clock = clock,
    )

    @Before
    fun resetAvailability() {
        WebViewAvailability.reset()
    }

    @After
    fun clearAvailability() {
        WebViewAvailability.reset()
    }

    @Test
    fun `warm-up runs once and the replay carries the jar cookie`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay(ReplayOutcome.Response(200, "payload", mapOf("content-type" to "text/html")))

        val outcome = session(warmup, cookies, replay).execute(warmHost, mapOf("Accept" to "text/html"), 5_000)

        assertEquals(1, warmup.calls)
        assertEquals(listOf<String?>("cf_clearance=fresh"), replay.cookieHeaders)
        val ok = outcome as ChannelOutcome.Ok
        assertEquals(200, ok.response.code)
        assertEquals("payload", ok.response.body)
        assertEquals("webview", ok.response.channel)
    }

    @Test
    fun `warm-up budget is the caller's timeout capped at the default`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay()

        session(warmup, cookies, replay).execute(warmHost, emptyMap(), 2_000)
        assertEquals(2_000L, warmup.lastTimeoutMs)

        val second = "https://slow.test/page"
        cookies.header = null
        session(warmup, cookies, replay).execute(second, emptyMap(), 90_000)
        assertEquals(WARMUP_TIMEOUT_MS, warmup.lastTimeoutMs)
    }

    @Test
    fun `a page that never stops being challenged is refused without a replay`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies, WarmupResult.StillChallenged("just a moment"))
        val replay = FakeReplay()

        val outcome = session(warmup, cookies, replay).execute(warmHost, emptyMap(), 1_000)

        assertTrue(outcome is ChannelOutcome.Refused)
        assertTrue((outcome as ChannelOutcome.Refused).reason.contains("just a moment"))
        assertEquals(0, replay.calls)
    }

    @Test
    fun `a warm-up timeout fails the channel`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies, WarmupResult.TimedOut)
        val replay = FakeReplay()

        val outcome = session(warmup, cookies, replay).execute(warmHost, emptyMap(), 1_000)

        assertTrue(outcome is ChannelOutcome.Failed)
        assertTrue((outcome as ChannelOutcome.Failed).error is SocketTimeoutException)
    }

    @Test
    fun `a fatal warm-up failure stops retrying that host`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies, WarmupResult.Failed("no renderer", fatal = true))
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay)

        assertTrue(channel.execute(warmHost, emptyMap(), 1_000) is ChannelOutcome.Failed)
        assertTrue(channel.execute(warmHost, emptyMap(), 1_000) is ChannelOutcome.Failed)

        assertEquals(1, warmup.calls)
        assertEquals(0, replay.calls)
    }

    @Test
    fun `a missing webview is failed once and then short-circuited`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies, WarmupResult.Unavailable)
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay)

        val first = channel.execute(warmHost, emptyMap(), 1_000)
        val second = channel.execute("https://other.test/page", emptyMap(), 1_000)

        assertTrue(first is ChannelOutcome.Failed)
        assertTrue(second is ChannelOutcome.Failed)
        assertEquals(1, warmup.calls)
    }

    @Test
    fun `a stale clearance triggers exactly one re-warm`() = runBlocking {
        val cookies = FakeCookies(header = "cf_clearance=stale")
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay(
            ReplayOutcome.Challenged(403, "cf-browser-verification"),
            ReplayOutcome.Response(200, "payload", emptyMap()),
        )

        val outcome = session(warmup, cookies, replay).execute(warmHost, emptyMap(), 5_000)

        assertEquals(1, warmup.calls)
        assertEquals(2, replay.calls)
        assertTrue(outcome is ChannelOutcome.Ok)
    }

    @Test
    fun `a fresh warm-up that is still refused is not warmed twice`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay(ReplayOutcome.Challenged(403, "just a moment"))

        val outcome = session(warmup, cookies, replay).execute(warmHost, emptyMap(), 5_000)

        assertEquals(1, warmup.calls)
        assertEquals(1, replay.calls)
        assertTrue(outcome is ChannelOutcome.Refused)
    }

    @Test
    fun `warm-up is skipped inside the ttl and repeated after it`() = runBlocking {
        var now = 0L
        val cookies = FakeCookies()
        // No clearance cookie in this scenario: only the ttl can keep the host warm.
        val warmup = FakeWarmup(cookies, WarmupResult.Solved(null))
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay, clock = { now })

        channel.execute(warmHost, emptyMap(), 1_000)
        now = 500L
        channel.execute(warmHost, emptyMap(), 1_000)
        assertEquals(1, warmup.calls)

        now = 2_000L
        channel.execute(warmHost, emptyMap(), 1_000)
        assertEquals(2, warmup.calls)
    }

    @Test
    fun `a clearance cookie from a previous process skips the warm-up`() = runBlocking {
        val cookies = FakeCookies(header = "cf_clearance=leftover")
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay()

        session(warmup, cookies, replay).execute(warmHost, emptyMap(), 1_000)

        assertEquals(0, warmup.calls)
        assertEquals(1, replay.calls)
    }

    @Test
    fun `clearHost drops cookies and the warm mark`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay)

        channel.execute(warmHost, emptyMap(), 1_000)
        channel.clearHost("warm.test")
        channel.execute(warmHost, emptyMap(), 1_000)

        assertEquals(listOf("warm.test"), cookies.clearedHosts)
        assertEquals(2, warmup.calls)
    }

    @Test
    fun `clearAll wipes the jar and forgets every host`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay)

        channel.execute(warmHost, emptyMap(), 1_000)
        channel.clearAll()
        channel.execute(warmHost, emptyMap(), 1_000)

        assertEquals(1, cookies.clearedAll)
        assertEquals(2, warmup.calls)
    }

    @Test
    fun `a warm-up that throws is a failure, not a crash`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies).apply { failure = IllegalStateException("main looper gone") }
        val replay = FakeReplay()
        val channel = session(warmup, cookies, replay)

        assertTrue(channel.execute(warmHost, emptyMap(), 1_000) is ChannelOutcome.Failed)
        // Not fatal: the next call may still succeed.
        channel.execute(warmHost, emptyMap(), 1_000)
        assertEquals(2, warmup.calls)
    }

    @Test
    fun `replay transport errors are surfaced as failures`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val error = IOException("connection reset")
        val replay = FakeReplay(ReplayOutcome.Error(error))

        val outcome = session(warmup, cookies, replay).execute(warmHost, emptyMap(), 1_000)

        assertTrue(outcome is ChannelOutcome.Failed)
        assertSame(error, (outcome as ChannelOutcome.Failed).error)
    }

    @Test
    fun `a non-http url is rejected before any warm-up`() = runBlocking {
        val cookies = FakeCookies()
        val warmup = FakeWarmup(cookies)
        val replay = FakeReplay()

        val outcome = session(warmup, cookies, replay).execute("ftp://a.test/file", emptyMap(), 1_000)

        assertTrue(outcome is ChannelOutcome.Failed)
        assertTrue((outcome as ChannelOutcome.Failed).error is IllegalArgumentException)
        assertEquals(0, warmup.calls)
    }
}
