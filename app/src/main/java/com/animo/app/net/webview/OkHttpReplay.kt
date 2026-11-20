package com.animo.app.net.webview

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Replays the original request with the cookies the WebView left in the jar.
 *
 * The client is cloned per call only to carry a per-request `callTimeout`; the clone shares the
 * connection pool, dispatcher and TLS sessions with the app's single client, so this costs no
 * sockets. Requests are plain GETs — the ladder only hands this channel page/API URLs, which are
 * GET by definition.
 */
internal class OkHttpReplay(
    private val client: OkHttpClient,
    private val userAgent: String,
) : WebReplay {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String?,
        timeoutMs: Long,
    ): ReplayOutcome = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(url, headers, cookieHeader)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext ReplayOutcome.Error(
                IllegalArgumentException("webview replay could not build a request", error),
            )
        }

        val callClient = if (timeoutMs > 0) {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        } else {
            client
        }

        try {
            callClient.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                val code = response.code
                val marker = ChallengeDetector.markerInDocument(body)
                when {
                    // A challenge is usually a 403, but Cloudflare also serves the interstitial
                    // with a 200/503, so the body marker decides whenever one is found.
                    marker != null -> ReplayOutcome.Challenged(code, marker)
                    code == 403 || code == 429 -> ReplayOutcome.Challenged(code, "http $code")
                    code in 200..299 -> ReplayOutcome.Response(
                        code = code,
                        body = body,
                        headers = response.headers.toMultimap()
                            .mapValues { (_, values) -> values.joinToString(", ") },
                    )
                    else -> ReplayOutcome.Error(IOException("webview replay returned HTTP $code"))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ReplayOutcome.Error(error)
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String?,
    ): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) ->
            // Cookie and User-Agent are owned here: the jar's cookies are authoritative, and the
            // UA must match the one the WebView presented — Cloudflare binds cf_clearance to it.
            if (name.equals("Cookie", ignoreCase = true)) return@forEach
            if (name.equals("User-Agent", ignoreCase = true)) return@forEach
            // One malformed caller header must not cost the whole replay.
            runCatching { builder.header(name, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", userAgent)
        }
        if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
        return builder.build()
    }
}
