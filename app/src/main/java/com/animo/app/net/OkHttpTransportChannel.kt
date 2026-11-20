package com.animo.app.net

import com.animo.app.data.remote.HttpClientFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The ladder's first rung: the shared OkHttp client, with the desktop fingerprint attached by the
 * caller via [BrowserHeaders].
 *
 * Nothing here retries. OkHttp's own connection-level retry stays on (a dropped keep-alive is not a
 * block signal), but a 403/429/challenge is reported as [ChannelOutcome.Refused] on the first hit so
 * the router can move to the next rung instead of spending the caller's timeout on the same host.
 */
class OkHttpTransportChannel(
    private val client: OkHttpClient = HttpClientFactory.create(),
    override val name: String = "okhttp",
) : TransportChannel {

    /**
     * One cloned client per distinct timeout. `callTimeout` is what actually interrupts a stalled
     * socket read — the coroutine timeout alone cannot, because a blocking read does not reach a
     * suspension point — so it has to be set per call; caching by value keeps the clone count at the
     * handful of timeouts the app ever uses instead of one per request.
     */
    private val timed = ConcurrentHashMap<Long, OkHttpClient>()

    override suspend fun execute(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): ChannelOutcome = attempt(url, headers, timeoutMs, body = null)

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): ChannelOutcome = attempt(url, headers, timeoutMs, body = body)

    private suspend fun attempt(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        body: String?,
    ): ChannelOutcome {
        val timeout = timeoutMs.coerceAtLeast(1_000L)
        val request = try {
            build(url, headers, body)
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects a non-http url here; that is a caller bug, not a block.
            return ChannelOutcome.Failed(IOException("bad request url: ${e.message.orEmpty()}", e))
        }

        return try {
            withTimeout(timeout) { send(request, timeout) }
        } catch (e: TimeoutCancellationException) {
            ChannelOutcome.Failed(IOException("timeout after ${timeout}ms", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ChannelOutcome.Failed(e)
        }
    }

    private fun build(url: String, headers: Map<String, String>, body: String?): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return if (body == null) {
            builder.get().build()
        } else {
            // A body with no caller-supplied type is form-ish plain text, which is what every
            // third-party POST endpoint here expects.
            builder.post(body.toRequestBody(PLAIN.toMediaType())).build()
        }
    }

    private suspend fun send(request: Request, timeoutMs: Long): ChannelOutcome = withContext(Dispatchers.IO) {
        val call = clientFor(timeoutMs).newCall(request)
        val response = call.await()
        try {
            classify(response, readBounded(response))
        } finally {
            response.close()
        }
    }

    private fun classify(response: Response, body: String): ChannelOutcome {
        val code = response.code
        return when {
            code == 403 || code == 429 -> ChannelOutcome.Refused("http $code")
            ChallengeMarkers.present(body) -> ChannelOutcome.Refused("challenge page with http $code")
            else -> ChannelOutcome.Ok(
                TransportResponse(
                    code = code,
                    body = body,
                    headers = response.headers.asMap(),
                    channel = name,
                ),
            )
        }
    }

    private fun clientFor(timeoutMs: Long): OkHttpClient = timed.getOrPut(timeoutMs) {
        client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    }

    /**
     * Reads at most [MAX_BODY_BYTES]. Page shells are ~13 KB and the largest JSON payload the app
     * sees is a few hundred KB, so the cap only ever bites on a document nobody could parse anyway;
     * truncating it keeps a misbehaving host from becoming an OOM instead of an error.
     */
    private fun readBounded(response: Response): String {
        val body = response.body ?: return ""
        val charset = body.contentType()?.charset() ?: Charsets.UTF_8
        val declared = body.contentLength()
        val initial = if (declared in 0L..MAX_BODY_BYTES.toLong()) declared.toInt() else CHUNK_BYTES
        val out = ByteArrayOutputStream(initial)
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0

        body.byteStream().use { stream ->
            while (total < MAX_BODY_BYTES) {
                val read = stream.read(chunk, 0, minOf(chunk.size, MAX_BODY_BYTES - total))
                if (read < 0) break
                out.write(chunk, 0, read)
                total += read
            }
        }
        return String(out.toByteArray(), charset)
    }

    /**
     * `enqueue` rather than `execute` so cancellation cancels the call: this is the only way a
     * resolver that is abandoned (user left the screen) stops holding a socket.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            runCatching { cancel() }
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            },
        )
    }

    /** okhttp keeps repeated names; the app only ever reads the last one per name. */
    private fun Headers.asMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>(size)
        for (index in 0 until size) map[name(index)] = value(index)
        return map
    }

    private companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val CHUNK_BYTES = 16 * 1024
        const val PLAIN = "text/plain; charset=utf-8"
    }
}
