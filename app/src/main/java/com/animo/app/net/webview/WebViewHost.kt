package com.animo.app.net.webview

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.animo.app.ui.common.AppWindowRef

/**
 * The window-attachment seam of the last rung.
 *
 * A WebView that is built but never attached to a window is a WebView the platform may never lay
 * out; on the MediaTek/Android 10 class ROMs in the field that also means page JavaScript does not
 * run, so a Cloudflare interstitial can never finish and every warm-up ends in a timeout with no
 * HTTP status at all.
 *
 * Splitting attachment out behind an interface keeps the decision — attach, fall back, detach
 * before destroy — plain Kotlin, testable on the JVM with a fake view; the Android half
 * ([DecorWindowHost]) is device-only, as is the WebView itself.
 *
 * [V] is the view type: `View` for the real host, anything for a fake. Contravariant, because a
 * host only ever consumes the view it is handed.
 */
internal interface WebViewHost<in V : Any> {

    /** @return true when [view] is now in a live window hierarchy and can be laid out. */
    fun attach(view: V): Boolean

    /** Removes [view] from the window hierarchy. Must tolerate a view that is already gone. */
    fun detach(view: V)
}

/**
 * Attaches the warm-up WebView to the resumed Activity's `decorView`.
 *
 * The view stays **on screen** on purpose: a WebView clipped off-screen or in `GONE` state can be
 * treated as not visible (Android 10 window occlusion, `isShown()`), which is the very failure this
 * class exists to avoid. Instead it is one pixel in the corner, fully transparent, and neither
 * focusable nor clickable, so it cannot be seen or take touch.
 */
internal class DecorWindowHost(private val activity: Activity) : WebViewHost<View> {

    override fun attach(view: View): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        // Attaching to a decor that is not in a window would reintroduce the bug this fixes.
        if (!decor.isAttachedToWindow) return false
        if (view.parent != null) return false

        view.layoutParams = ViewGroup.LayoutParams(SIZE_PX, SIZE_PX)
        // `newWebView` leaves the throw-away view GONE, and a GONE child is never laid out.
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.isFocusable = false
        view.isClickable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return try {
            decor.addView(view)
            true
        } catch (error: Throwable) {
            view.parent?.let { (it as? ViewGroup)?.removeView(view) }
            throw error
        }
    }

    override fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private companion object {
        /** 1 px at the top-left corner: invisible under `alpha = 0` and too small to hit. */
        const val SIZE_PX = 1
    }
}

/** The host for the current Activity, or `null` when there is no resumed Activity to attach to. */
internal fun currentWindowHost(): WebViewHost<View>? =
    AppWindowRef.activity()?.let { activity -> DecorWindowHost(activity) }

/**
 * One warm-up's relationship with the window: attach at most once, detach exactly what was
 * attached, and never detach something a host refused. Exceptions are left to the caller so it can
 * log them and carry on unattached instead of failing the request.
 */
internal class WindowSlot<V : Any>(private val view: V) {

    private var host: WebViewHost<V>? = null

    val isAttached: Boolean get() = host != null

    /** @return true when [view] ended up in a window; `false` means "warm up unattached". */
    fun attach(candidate: WebViewHost<V>?): Boolean {
        if (candidate == null || host != null) return false
        val attached = candidate.attach(view)
        if (attached) host = candidate
        return attached
    }

    /** Idempotent; a no-op when nothing was ever attached. */
    fun detach() {
        val current = host ?: return
        host = null
        current.detach(view)
    }
}
