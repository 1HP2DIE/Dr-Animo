package com.animo.app.net.webview

import com.animo.app.AppLog
import com.animo.app.net.ChannelOutcome
import com.animo.app.net.TransportResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Warm-up budget when the caller does not impose a tighter one.
 *
 * 25 s, not 15: an interstitial on a mobile connection routinely needs more than fifteen seconds
 * of JavaScript before it hands over the clearance cookie, and a first attempt that gives up early
 * leaves the host marked cold for the rest of the session.
 */
internal const val WARMUP_TIMEOUT_MS: Long = 25_000L

/**
 * How long a successful warm-up is trusted before the WebView is asked again. The clearance cookie
 * is the real authority (the replay proves it), this only avoids re-warming on every call.
 */
internal const val WARM_TTL_MS: Long = 10 * 60_000L

/**
 * The testable half of [WebViewChannel]: every WebView, cookie-jar and HTTP detail sits behind
 * [BrowserWarmup], [CookieStore] and [WebReplay], so the flow below is plain Kotlin.
 *
 * Flow for one request:
 * 1. If the host is known-broken in this process, fail fast.
 * 2. If the host is warm (recent success, or a clearance cookie already in the jar), skip ahead.
 * 3. Otherwise warm once, under a lock, so two concurrent requests for a slow host cannot open
 *    two WebViews.
 * 4. Replay the original request over OkHttp with the jar's cookies.
 * 5. If the replay is still challenged *and* we skipped the warm-up in step 2, the leftover
 *    clearance is stale: one forced warm-up, one replay. Never a loop.
 */
internal class WebViewSession(
    private val warmup: BrowserWarmup,
    private val cookies: CookieStore,
    private val replay: WebReplay,
    private val clearanceCookieNames: Set<String> = ChallengeDetector.DEFAULT_CLEARANCE_COOKIES,
    private val warmTtlMs: Long = WARM_TTL_MS,
    private val warmupTimeoutMs: Long = WARMUP_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val channelName: String = WebViewChannel.NAME,
) {

    /**
     * Serialises warm-ups across hosts. A WebView is 20-100 MB while it holds a page, so two in
     * flight at once is exactly what a low-RAM device cannot afford; waiting for another host is
     * cheaper than an OOM kill.
     */
    private val warmLock = Mutex()

    /** host -> wall-clock instant until which the warm-up is trusted. */
    private val warmUntil = ConcurrentHashMap<String, Long>()

    suspend fun execute(url: String, headers: Map<String, String>, timeoutMs: Long): ChannelOutcome {
        val target = url.toHttpUrlOrNull()
            ?: return ChannelOutcome.Failed(
                IllegalArgumentException("webview channel needs an absolute http(s) url"),
            )
        val host = target.host

        if (WebViewAvailability.isBroken(host)) {
            return ChannelOutcome.Failed(WebViewUnavailableException("webview disabled for $host in this process"))
        }

        val budget = if (timeoutMs > 0) minOf(timeoutMs, warmupTimeoutMs) else warmupTimeoutMs
        val alreadyWarm = isWarm(host, url)

        if (!alreadyWarm) {
            when (val step = warmUp(url, host, budget)) {
                is WarmStep.Stop -> return step.outcome
                WarmStep.Ready -> Unit
            }
        }

        var outcome = replayNow(url, host, headers, timeoutMs)

        if (outcome is ChannelOutcome.Refused && alreadyWarm) {
            // We trusted a cookie/ttl from before this call and the edge disagreed: one forced
            // re-warm, then one replay. A second failure is the answer, not another WebView.
            if (warmUp(url, host, budget, force = true) is WarmStep.Ready) {
                outcome = replayNow(url, host, headers, timeoutMs)
            }
        }

        return outcome
    }

    fun clearHost(host: String) {
        warmUntil.remove(host)
        runCatching { cookies.clearHost(host) }
    }

    fun clearAll() {
        warmUntil.clear()
        runCatching { cookies.clearAll() }
    }

    private fun isWarm(host: String, url: String): Boolean {
        val until = warmUntil[host]
        if (until != null) {
            if (until > clock()) return true
            warmUntil.remove(host)
        }
        // Cookies outlive the process: a clearance left over from a previous launch means there is
        // nothing for the WebView to do until a replay proves it stale (step 5 above).
        return hasClearanceCookie(url)
    }

    private fun hasClearanceCookie(url: String): Boolean = ChallengeDetector.hasClearance(
        runCatching { cookies.cookieHeader(url) }.getOrNull(),
        clearanceCookieNames,
    )

    private suspend fun warmUp(
        url: String,
        host: String,
        budget: Long,
        force: Boolean = false,
    ): WarmStep = warmLock.withLock {
        if (!force && isWarm(host, url)) return@withLock WarmStep.Ready

        warmUntil.remove(host)
        val result = try {
            warmup.warm(url, budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WarmupResult.Failed("warm-up threw", fatal = false, cause = error)
        }

        when (result) {
            is WarmupResult.Solved -> {
                warmUntil[host] = clock() + warmTtlMs
                AppLog.d("webview: warm-up solved for $host")
                WarmStep.Ready
            }

            WarmupResult.TimedOut -> WarmStep.Stop(
                ChannelOutcome.Failed(
                    SocketTimeoutException("webview warm-up timed out after ${budget}ms"),
                ),
            )

            is WarmupResult.StillChallenged -> {
                AppLog.d("webview: still challenged for $host (${result.marker ?: "unknown marker"})")
                WarmStep.Stop(
                    ChannelOutcome.Refused(
                        "challenge not solved" + (result.marker?.let { ": $it" } ?: ""),
                    ),
                )
            }

            WarmupResult.Unavailable -> {
                WebViewAvailability.markUnavailable()
                AppLog.w("webview: no usable System WebView on this device")
                WarmStep.Stop(ChannelOutcome.Failed(WebViewUnavailableException("no usable System WebView")))
            }

            is WarmupResult.Failed -> {
                if (result.fatal) WebViewAvailability.markHostBroken(host)
                AppLog.w("webview: warm-up failed for $host: ${result.message}", result.cause)
                WarmStep.Stop(ChannelOutcome.Failed(result.cause ?: IOException(result.message)))
            }
        }
    }

    private suspend fun replayNow(
        url: String,
        host: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): ChannelOutcome {
        val cookieHeader = runCatching { cookies.cookieHeader(url) }.getOrNull()
        val outcome = try {
            replay.get(url, headers, cookieHeader, timeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReplayOutcome.Error(error)
        }

        return when (outcome) {
            is ReplayOutcome.Response -> {
                warmUntil[host] = clock() + warmTtlMs
                ChannelOutcome.Ok(
                    TransportResponse(outcome.code, outcome.body, outcome.headers, channelName),
                )
            }

            is ReplayOutcome.Challenged -> {
                warmUntil.remove(host)
                ChannelOutcome.Refused("still challenged (http ${outcome.code}): ${outcome.marker}")
            }

            is ReplayOutcome.Error -> ChannelOutcome.Failed(outcome.error)
        }
    }

    private sealed interface WarmStep {
        data object Ready : WarmStep
        data class Stop(val outcome: ChannelOutcome) : WarmStep
    }
}

/** Signals "do not ask this host again in this process"; the ladder skips to its next rung. */
internal class WebViewUnavailableException(message: String) : IOException(message)
