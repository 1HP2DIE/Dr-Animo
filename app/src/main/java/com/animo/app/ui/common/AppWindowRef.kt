package com.animo.app.ui.common

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * The resumed [Activity] the app currently has, held weakly.
 *
 * Why it exists: an `android.webkit.WebView` that is never attached to a window is never laid out,
 * and on several ROMs (MediaTek/Android 10 class in particular) it does not run page JavaScript at
 * all — so an edge challenge interstitial can never finish and the warm-up always times out.
 * `net.webview` needs a `ViewGroup` to park a one-pixel, fully transparent WebView in.
 *
 * The WebView layer must not hold the Activity itself and must not pull in a lifecycle artifact,
 * so the Activity is published here from `MainActivity` and read back as a [WeakReference] at call
 * time. A hint from the UI layer to the network layer, not a dependency between them: only
 * [activity] is consumed, and it returns `null` after a configuration change or when backgrounded.
 */
object AppWindowRef {

    @Volatile
    private var current: WeakReference<Activity>? = null

    /** Called from `MainActivity.onResume`; the previous Activity is not kept. */
    fun register(activity: Activity) {
        current = WeakReference(activity)
    }

    /**
     * Called from `onPause`/`onDestroy`. Only clears when [activity] is still the registered one, so
     * a pause of the outgoing Activity cannot wipe the registration of the incoming one.
     */
    fun clear(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    /** The resumed Activity, or `null` when none is live (backgrounded, finishing, destroyed). */
    fun activity(): Activity? = current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
