package com.animo.app.net.webview

import android.content.Context
import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.net.ChannelOutcome
import com.animo.app.net.TransportChannel
import okhttp3.OkHttpClient

/**
 * The last rung of the fallback ladder: when a host answers plain HTTP with a 403 or a JavaScript
 * challenge, let the **System WebView** that is already installed on the phone solve it, keep the
 * cookies Android's `CookieManager` collects, and replay the request over OkHttp.
 *
 * ## Why a WebView is acceptable in a Compose-only app
 * - **Zero APK cost.** The System WebView ships with the device; this adds no dependency, no
 *   bundled browser and no extra application size.
 * - **It is never seen.** No Activity, no Fragment, no `AndroidView`, no window: the WebView is
 *   constructed off-screen, put in `GONE` state, loaded once, and destroyed. It renders no app
 *   UI, so the Compose-only rule holds.
 * - **No bridge.** No `addJavascriptInterface`; the page can only talk to its own origin.
 * - **Bounded.** One WebView at a time (a process-wide lock), a hard timeout (15 s by default),
 *   `destroy()` in a `finally`, and a process-wide flag that stops retrying a device or host that
 *   cannot warm up.
 *
 * ## What it cannot do
 * A clearance cookie is issued to a specific client: same IP, same User-Agent, and — on the
 * stricter edges — the same TLS/JA3 fingerprint. OkHttp and Chromium do not share a TLS stack, so
 * a replay can still be refused with valid cookies. That case is reported as
 * [ChannelOutcome.Refused] and the ladder moves on; it is not retried in a loop. The reverse case
 * (replay succeeds) is the common one for cookie-based checks.
 *
 * ## Lifecycle
 * - `execute` is the only entry point used by the ladder.
 * - [clearHost] / [clearAll] drop the jars on logout or when the network changed (a clearance is
 *   bound to an IP, so a Wi-Fi↔mobile switch usually invalidates it).
 */
class WebViewChannel internal constructor(
    warmup: BrowserWarmup,
    private val cookies: CookieStore,
    replay: WebReplay,
    clearanceCookieNames: Set<String> = ChallengeDetector.DEFAULT_CLEARANCE_COOKIES,
    warmupTimeoutMs: Long = WARMUP_TIMEOUT_MS,
    warmTtlMs: Long = WARM_TTL_MS,
    clock: () -> Long = System::currentTimeMillis,
) : TransportChannel {

    private val session = WebViewSession(
        warmup = warmup,
        cookies = cookies,
        replay = replay,
        clearanceCookieNames = clearanceCookieNames,
        warmTtlMs = warmTtlMs,
        warmupTimeoutMs = warmupTimeoutMs,
        clock = clock,
    )

    /**
     * @param timeoutMs warm-up budget per host; it is also the ceiling for the caller's own
     *   timeout, so a request never spends longer here than the ladder gave it.
     * @param userAgent the UA both the WebView and the replay send. It must be one value:
     *   a clearance cookie is bound to the UA that earned it.
     */
    constructor(
        context: Context,
        client: OkHttpClient,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        userAgent: String = HttpClientFactory.UA_DESKTOP,
    ) : this(
        // Two stateless wrappers over the same process-wide CookieManager: the warm-up needs to
        // see the clearance appear, the replay needs to read it back.
        warmup = AndroidBrowserWarmup(
            context = context.applicationContext ?: context,
            userAgent = userAgent,
            cookies = AndroidCookieStore(),
        ),
        cookies = AndroidCookieStore(),
        replay = OkHttpReplay(client, userAgent),
        warmupTimeoutMs = timeoutMs,
    )

    override val name: String = NAME

    override suspend fun execute(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): ChannelOutcome = session.execute(url, headers, timeoutMs)

    /** Forgets [host]: drops its cookies and the "already warm" mark. */
    fun clearHost(host: String) = session.clearHost(host)

    /** Drops every cookie the WebView jar holds. Used on logout. */
    fun clearAll() = session.clearAll()

    companion object {
        const val NAME: String = "webview"

        /** 15 s: long enough for a JS challenge on a slow phone, short enough to keep the ladder quick. */
        const val DEFAULT_TIMEOUT_MS: Long = WARMUP_TIMEOUT_MS
    }
}
