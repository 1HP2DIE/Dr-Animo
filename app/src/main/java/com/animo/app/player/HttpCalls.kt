package com.animo.app.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call

/**
 * Hangs the socket up when the calling coroutine is cancelled.
 *
 * `Call.execute()` blocks for as long as the peer takes; leaving it parked after the viewer has
 * gone (or the episode changed) keeps an IO thread busy until the read timeout fires. Registering
 * the cancel as a completion handler is the only way to interrupt a blocking call.
 */
internal suspend fun Call.cancelOnCancellation() {
    val job = currentCoroutineContext()[Job] ?: return
    job.invokeOnCompletion { cancel() }
}
