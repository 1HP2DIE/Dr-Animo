package com.animo.app.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.webkit.CookieManager
import com.animo.app.AppLog
import com.animo.app.net.BrowserHeaders
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The single OkHttp client for the whole app (catalog, resolver, Coil, Media3 all share it so TLS
 * sessions and the connection pool are reused).
 *
 * Three things are wired in here rather than in every provider, because every provider got at least
 * one of them wrong before:
 *
 * 1. **Browser headers** — [BrowserHeadersInterceptor] fills any header the caller did not set,
 *    from the request's own host. A provider that pins its own `User-Agent`/`Referer` still wins;
 *    one that forgets no longer fingerprints as a bare `okhttp/4.12.0`.
 * 2. **Cookies** — [cookieJar] is `android.webkit.CookieManager`, i.e. Chromium's own store. The
 *    invisible WebView warm-up and OkHttp therefore share one cookie set instead of two, and
 *    Cloudflare's `cf_clearance` survives process death without a byte of our own persistence code.
 * 3. **Network changes** — a default-network callback drops the cookie jar and tells the transport
 *    that its per-host budget is spent. Clearance cookies are IP-bound: after a WiFi↔LTE switch the
 *    old cookie is worthless at best and bot-shaped at worst.
 *
 * Deliberately **log-free** per request: no HttpLoggingInterceptor, no body dumps. Episode payloads
 * are large and the API returns no secrets, so there is nothing worth the I/O.
 */
object HttpClientFactory {

    /** Desktop Chrome UA — the API and megaplay both serve the useful payloads to a browser UA. */
    const val UA_DESKTOP: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * The process cookie store. `CookieManager` is a singleton anyway, so this is the *same* store
     * the WebView rung writes into — that is the whole point, and it is why nothing here is
     * file-backed: a second store would have to be kept in sync with Chromium's.
     */
    val cookieJar: PlatformCookieJar by lazy { PlatformCookieJar() }

    private val networkListeners = CopyOnWriteArrayList<() -> Unit>()

    private val networkWatched = AtomicBoolean(false)

    /**
     * Called after the default network changes and [cookieJar] has been cleared. Used by the
     * transport to reset its per-host budget: a host that refused us on one network deserves one
     * clean attempt on the next.
     */
    fun addNetworkChangeListener(listener: () -> Unit) {
        networkListeners += listener
    }

    /**
     * The client without the network-change hook. Kept because [OkHttpTransportChannel] defaults its
     * `client` to this call: a transport built outside the container still gets the jar and the
     * fingerprint, just not the "new network, new cookies" reset.
     */
    fun create(): OkHttpClient = build(null)

    /** The client the app uses: cookies, fingerprint and the network-change reset. */
    fun create(context: Context): OkHttpClient = build(context.applicationContext)

    private fun build(context: Context?): OkHttpClient {
        // Binder registration only: no I/O, no DNS, no connection. The cookie jar is not touched
        // until a real change happens, so this stays free at start-up.
        if (context != null) watchNetwork(context)

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor(BrowserHeadersInterceptor)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 4
                    maxRequestsPerHost = 2
                },
            )
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * One callback for the process lifetime, never unregistered: a network event between the app
     * being backgrounded and killed must still be seen by whatever runs next.
     */
    private fun watchNetwork(context: Context) {
        if (!networkWatched.compareAndSet(false, true)) return

        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            AppLog.w("no ConnectivityManager: network-change cookie reset disabled")
            return
        }

        // Handle of the network we believe we are on. The first `onAvailable` after start-up is the
        // baseline, not a change: registering a default callback replays the current network, and
        // treating that as a switch would wipe the jar on every cold start.
        val currentHandle = AtomicLong(0L)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previous = currentHandle.getAndSet(handle)
                if (previous == 0L || previous == handle) return

                AppLog.i("network changed ($previous -> $handle): clearing cookies")
                cookieJar.clear()
                networkListeners.forEach { listener ->
                    runCatching(listener).onFailure { AppLog.w("network listener failed", it) }
                }
            }
        }

        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (t: Throwable) {
            AppLog.w("network callback not registered", t)
        }
    }
}

/**
 * Every OkHttp cookie lives in the WebView's store, and every WebView cookie is visible to OkHttp.
 *
 * `CookieManager.getCookie` answers with `name=value` pairs only — no domain, path or expiry — so
 * the jar rebuilds request cookies with the URL it was asked about: `hostOnly` and the real path
 * scoping were already applied by Chromium when it decided to return them.
 */
class PlatformCookieJar : CookieJar {

    private val store: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }
            .onFailure { AppLog.w("WebView cookie store unavailable", it) }
            .getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = store ?: return
        for (cookie in cookies) {
            runCatching { manager.setCookie(url.toString(), setCookieHeader(cookie)) }
                .onFailure { AppLog.w("cookie not stored for ${url.host}", it) }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = store ?: return emptyList()
        val header = runCatching { manager.getCookie(url.toString()) }.getOrNull()
        if (header.isNullOrEmpty()) return emptyList()

        val cookies = ArrayList<Cookie>(4)
        var start = 0
        while (start < header.length) {
            val separator = header.indexOf(';', start)
            val end = if (separator == -1) header.length else separator
            val pair = header.substring(start, end)
            start = end + 1

            // Values may contain '=' (padding, embedded tokens): only the first one separates.
            val equals = pair.indexOf('=')
            if (equals <= 0) continue
            val name = pair.substring(0, equals).trim()
            if (name.isEmpty()) continue

            val cookie = runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(pair.substring(equals + 1).trim())
                    .path("/")
                    .hostOnlyDomain(url.host)
                    .expiresAt(Long.MAX_VALUE)
                    .apply { if (url.isHttps) secure() }
                    .build()
            }.getOrNull()

            if (cookie != null) cookies += cookie
        }
        return cookies
    }

    /** Drops everything, including `cf_clearance`. Async in WebView; no callback needed. */
    fun clear() {
        runCatching { store?.removeAllCookies(null) }
            .onFailure { AppLog.w("cookie store not cleared", it) }
    }

    /**
     * Re-serialises an OkHttp cookie as `Set-Cookie`. OkHttp's own `toString()` is only *close* —
     * it writes an absolute `Expires` for session cookies too — and Chromium would then keep a
     * session cookie as a year-long one. Here a session cookie stays a session cookie.
     */
    private fun setCookieHeader(cookie: Cookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        if (!cookie.hostOnly) append("; Domain=").append(cookie.domain)
        append("; Path=").append(cookie.path)
        if (cookie.persistent) {
            val seconds = (cookie.expiresAt - System.currentTimeMillis()) / 1000
            // A negative Max-Age is how a deletion arrives; clamp so WebView deletes it.
            append("; Max-Age=").append(seconds.coerceAtLeast(0L))
        }
        if (cookie.secure) append("; Secure")
        if (cookie.httpOnly) append("; HttpOnly")
    }
}

/**
 * Fills in the browser fingerprint, but only where the caller left a hole. A provider that pins its
 * own UA or `Referer` keeps it; every other request stops looking like a bare OkHttp client.
 *
 * Stateless, so one instance serves every clone the transport makes for its per-call timeouts.
 */
private object BrowserHeadersInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val additions = runCatching { BrowserHeaders.forUrl(request.url.toString()) }
            .getOrDefault(emptyMap())
        if (additions.isEmpty()) return chain.proceed(request)

        var mutated: Request.Builder? = null
        for ((name, value) in additions) {
            if (value.isBlank() || request.header(name) != null) continue
            val builder = mutated ?: request.newBuilder().also { mutated = it }
            builder.addHeader(name, value)
        }
        return chain.proceed(mutated?.build() ?: request)
    }
}
