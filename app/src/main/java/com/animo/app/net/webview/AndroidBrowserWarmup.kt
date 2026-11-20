package com.animo.app.net.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.animo.app.AppLog
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads one URL in a throw-away [WebView] so an edge challenge can solve itself.
 *
 * The WebView is acceptable here only because of how little of it is used:
 * - it is never visible and renders no app UI — the app stays Compose-only and no
 *   Activity/Fragment/`AndroidView` is involved. It *is* parked as a 1 px, transparent child of the
 *   resumed Activity's `decorView` while it loads (see [DecorWindowHost]): unattached WebViews are
 *   not laid out on several ROMs and then never run the challenge script at all. With no resumed
 *   Activity it falls back to the old unattached path;
 * - it exists for a single page load, then `destroy()` runs in a `finally` and the reference is
 *   dropped (a live WebView holds tens of megabytes and a renderer process);
 * - no JavaScript bridge is registered: the page talks to its own origin, nothing talks back;
 * - dialogs are swallowed, so a hostile page can neither show UI nor block on `alert()`.
 *
 * A **new** WebView per call is intentional: reusing one across hosts keeps the previous origin's
 * renderer, storage and history alive, and those are exactly the things that cost memory.
 *
 * All of this must run on the main thread — WebView has no thread-safe construction or load.
 */
internal class AndroidBrowserWarmup(
    context: Context,
    private val userAgent: String,
    private val cookies: CookieStore,
    private val clearanceCookieNames: Set<String> = ChallengeDetector.DEFAULT_CLEARANCE_COOKIES,
) : BrowserWarmup {

    /**
     * Application context, not an Activity: the WebView outlives no Activity and must not keep one
     * alive through a context reference. When a window exists it comes from [AppWindowRef] as a weak
     * reference, used for one `addView`/`removeView` pair and never stored.
     */
    private val appContext: Context = context.applicationContext ?: context

    override suspend fun warm(url: String, timeoutMs: Long): WarmupResult =
        withContext(Dispatchers.Main.immediate) {
            if (WebViewAvailability.isBroken(hostOf(url))) return@withContext WarmupResult.Unavailable

            val webView = try {
                newWebView()
            } catch (error: Throwable) {
                // Some ROMs ship without a WebView provider; the constructor then throws. This is
                // permanent for the process, so it is reported as fatal.
                AppLog.w("webview: construction failed", error)
                return@withContext WarmupResult.Failed("WebView unavailable", fatal = true, cause = error)
            }

            val signal = CompletableDeferred<WarmupResult>()
            val client = WarmupClient(signal)
            // Attachment is decided per call: the Activity can appear or go away between warm-ups.
            val slot = WindowSlot(webView)
            try {
                attachToWindow(slot)
                webView.webViewClient = client
                webView.loadUrl(url)
                withTimeoutOrNull(timeoutMs) { signal.await() }
                    ?: client.settleOnTimeout(url)
            } catch (error: Throwable) {
                WarmupResult.Failed("warm-up failed", fatal = false, cause = error)
            } finally {
                // Order matters: a WebView must leave the hierarchy before `destroy()`. A failure
                // here is not worth a crash either — `release` removes it again as a backstop.
                runCatching { slot.detach() }
                    .onFailure { AppLog.w("webview: detach failed", it) }
                release(webView)
                cookies.flush()
            }
        }

    /**
     * Parks [webView] in the current Activity's window when one exists, so the page is laid out and
     * its JavaScript runs. Every failure mode — no Activity, a dead decor, a ROM that refuses the
     * add — ends as "warm up unattached", never as an error: the unattached path is what shipped
     * before and still works on most devices.
     */
    private fun attachToWindow(slot: WindowSlot<WebView>) {
        val attached = try {
            slot.attach(currentWindowHost())
        } catch (error: Throwable) {
            // No host in the message: this line can end up in a bug report.
            AppLog.w("webview: window attach failed, warming unattached", error)
            false
        }
        if (attached) AppLog.d("webview: warming attached to the activity window")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        // Same jar the replay reads; third-party cookies are on because some edges set it on the
        // registrable domain rather than the exact host.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
        }

        val webView = WebView(appContext)
        webView.settings.apply {
            // The challenge *is* JavaScript. Without this the page never settles and the warm-up
            // is pure cost.
            javaScriptEnabled = true
            domStorageEnabled = true
            // The clearance cookie is what we are after; its UA must match the replay's.
            userAgentString = userAgent
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // The page is a few hundred kilobytes of markup; images are never needed and only
            // cost memory on the device we are trying to be gentle with.
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) }

        webView.setBackgroundColor(Color.TRANSPARENT)
        // Unattached and GONE is the fallback shape. The window path flips this back to VISIBLE:
        // a GONE child is never laid out, and a WebView that is never laid out may never run its
        // page JavaScript.
        webView.visibility = View.GONE
        webView.isFocusable = false
        webView.isClickable = false
        // A page that calls alert()/confirm()/prompt() would otherwise wait on a dialog that has
        // no window to appear in. Consuming the call lets the script continue.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean = true
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean = true
            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }
        }
        return webView
    }

    /**
     * `stopLoading` → `destroy` on a detached WebView. `destroy()` alone is known to leave the
     * renderer process behind on some versions; stopping the load first gives it nothing to
     * finish. Every step is guarded: a WebView that is already gone must not throw during cleanup.
     */
    private fun release(webView: WebView) {
        runCatching {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            // Backstop for a detach that failed above: destroying an attached view leaves the
            // decor holding a dead WebView.
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
        AppLog.d("webview: released")
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty()
    /**
     * Watches the page. Cloudflare's interstitial rewrites itself (and reloads) after the
     * challenge is computed, so `onPageFinished` can fire several times; a marker therefore means
     * "keep waiting", never "give up". Only a clearance cookie or a marker-free page settles the
     * warm-up early — the timeout is the backstop.
     */
    private inner class WarmupClient(
        private val signal: CompletableDeferred<WarmupResult>,
    ) : WebViewClient() {

        var lastMarker: String? = null
            private set

        override fun onPageFinished(view: WebView, pageUrl: String) {
            if (signal.isCompleted || pageUrl.startsWith("about:")) return

            val cookiesNow = readCookies(pageUrl)
            if (ChallengeDetector.hasClearance(cookiesNow, clearanceCookieNames)) {
                signal.complete(WarmupResult.Solved(cookiesNow))
                return
            }

            // The title lives in <head>; the body check catches interstitials that push their
            // marker below the fold. Both are read in one round trip.
            view.evaluateJavascript(PROBE_JS) { raw ->
                if (signal.isCompleted) return@evaluateJavascript
                val (title, html) = decodeProbe(raw)
                val marker = ChallengeDetector.marker(title, html)
                if (marker != null) {
                    lastMarker = marker
                    return@evaluateJavascript
                }
                if (title.isBlank() && html.isBlank()) return@evaluateJavascript

                val settledCookies = readCookies(pageUrl) ?: cookiesNow
                signal.complete(WarmupResult.Solved(settledCookies))
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Sub-resource errors are noise (ad/tracker hosts fail on every load); only a failed
            // main frame means this URL cannot be warmed at all.
            if (!request.isForMainFrame) return
            signal.complete(
                WarmupResult.Failed("main frame error ${error.errorCode}", fatal = false),
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Returning false here kills the app: the framework treats an unhandled renderer
            // death as a fatal process error. We do not need the WebView more than once anyway.
            signal.complete(WarmupResult.Failed("webview renderer gone", fatal = false))
            return true
        }

        /** Called when the timeout fires; the page may have set the cookie just before that. */
        fun settleOnTimeout(url: String): WarmupResult {
            val cookiesNow = readCookies(url)
            if (ChallengeDetector.hasClearance(cookiesNow, clearanceCookieNames)) {
                return WarmupResult.Solved(cookiesNow)
            }
            return lastMarker?.let { WarmupResult.StillChallenged(it) } ?: WarmupResult.TimedOut
        }

        private fun readCookies(url: String): String? =
            runCatching { cookies.cookieHeader(url) }.getOrNull()
    }

    private companion object {

        /**
         * Returns `title \u0001 outerHTML[0..4096]`. The document head is where the interstitial
         * script tags and the title live, so 4 KB is enough to recognise every known challenge and
         * cheap enough to stringify on a phone.
         */
        const val PROBE_JS: String =
            "(function(){try{" +
                "var t=document.title||'';" +
                "var h=document.documentElement?document.documentElement.outerHTML:'';" +
                "return t+'\\u0001'+h.slice(0,4096);" +
                "}catch(e){return ''}})()"

        const val FIELD_SEPARATOR = '\u0001'
    }

    /**
     * `evaluateJavascript` hands back a JSON-encoded string (`null` for an undefined result), so
     * it has to be decoded before the separator means anything.
     */
    private fun decodeProbe(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank() || raw == "null") return "" to ""
        val decoded = runCatching {
            Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull
        }.getOrNull() ?: return "" to ""
        val fields = decoded.split(FIELD_SEPARATOR, limit = 2)
        return (fields.getOrNull(0) ?: "") to (fields.getOrNull(1) ?: "")
    }
}
