package com.animo.app.net.webview

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide memory of "the WebView cannot help here".
 *
 * WebView is not guaranteed to exist: some ROMs ship without it, some MDM images disable it, and
 * a handful of devices throw from the constructor. Retrying costs a main-thread hop and a
 * multi-megabyte allocation each time, so a host that failed fatally is skipped for the rest of
 * the process. The state is static by design — it describes the device, not a channel instance.
 */
internal object WebViewAvailability {

    @Volatile
    private var missing = false

    private val brokenHosts = ConcurrentHashMap.newKeySet<String>()

    fun isBroken(host: String): Boolean = missing || host in brokenHosts

    fun markUnavailable() {
        missing = true
    }

    fun markHostBroken(host: String) {
        brokenHosts += host
    }

    /** Tests share the process with the whole JVM; they must reset what they poison. */
    fun reset() {
        missing = false
        brokenHosts.clear()
    }
}
