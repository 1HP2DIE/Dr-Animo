package com.animo.app

import android.util.Log

/**
 * Single log tag for the whole app (logcat filtering stays trivial) and a compile-time kill
 * switch for verbose output. Never log request bodies: episode payloads are large and the sync
 * layer is the only place that sees them.
 */
object AppLog {

    const val TAG: String = "Animo"

    private const val VERBOSE = true

    fun d(message: String) {
        if (VERBOSE) Log.d(TAG, message)
    }

    fun i(message: String) = Log.i(TAG, message)

    fun w(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    }
}
