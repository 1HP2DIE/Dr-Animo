package com.animo.app.net.webview

import android.os.Build
import android.webkit.CookieManager
import com.animo.app.AppLog

/**
 * Thin wrapper over the process-wide [CookieManager].
 *
 * Deliberately: every platform call is `runCatching`-guarded. CookieManager is a static service
 * provided by the System WebView package; on a ROM where that package is missing or updated into
 * a broken state, `getInstance()` throws `MissingWebViewPackageException` or an
 * `IllegalStateException`. A cookie read must never take the app down — a `null` header simply
 * means the replay goes out without cookies and the ladder moves on.
 *
 * `getInstance()` is a different instance per call and the class is stateless, so constructing
 * this more than once is harmless.
 */
internal class AndroidCookieStore : CookieStore {

    override fun cookieHeader(url: String): String? {
        val manager = instance() ?: return null
        return runCatching { manager.getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * CookieManager has no "clear this domain" API, so each name the jar returns for [host] is
     * overwritten with `Max-Age=0`. That covers the challenge cookies, which are all host cookies
     * with `Path=/`.
     *
     * Limitation: a cookie scoped to a parent domain (`Domain=.example.com` while [host] is
     * `cdn.example.com`) is read but not deleted here. `clearAll()` is the thorough reset and is
     * what logout should use.
     */
    override fun clearHost(host: String) {
        val manager = instance() ?: return
        val base = "https://$host/"
        val current = runCatching { manager.getCookie(base) }.getOrNull()
        if (current.isNullOrBlank()) return
        ChallengeDetector.cookieNames(current).forEach { name ->
            runCatching { manager.setCookie(base, "$name=; Max-Age=0; Path=/") }
        }
        flush()
        AppLog.d("webview: cleared cookies for $host")
    }

    @Suppress("DEPRECATION")
    override fun clearAll() {
        val manager = instance() ?: return
        // removeAllCookies is async; flush from its callback so the disk copy cannot outlive the
        // in-memory one. Deprecated in API 30 but still the only whole-jar wipe available.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                manager.removeAllCookies { flush() }
            } else {
                manager.removeAllCookie()
            }
        }.onFailure { AppLog.w("webview: cookie wipe failed", it) }
    }

    override fun flush() {
        // flush() exists since API 21; guarded because a few ROMs still throw from it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val manager = instance() ?: return
        runCatching { manager.flush() }
    }

    private fun instance(): CookieManager? =
        runCatching { CookieManager.getInstance() }
            .onFailure { AppLog.w("webview: CookieManager unavailable", it) }
            .getOrNull()
}
