package com.animo.app.player.movielink

import com.animo.app.data.remote.HttpClientFactory
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.player.ResolveCache
import com.animo.app.player.ResolveException
import com.animo.app.player.StreamResolver
import com.animo.app.player.cancelOnCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * movielinkbd resolver: `/p/<token>` shell -> `window.__PD2` -> one playable URL.
 *
 * The chain is short and has no signing step:
 *  1. `GET /p/<token>` on the catalogue host returns the ~13 KB shell (Cloudflare wants a desktop
 *     browser UA; the app's UA is the same one the rest of the app sends).
 *  2. The shell embeds `window.__PD2` with a `master` (HLS) and an `mp4` (progressive) URL for every
 *     episode of the title.
 *  3. `fsl.movielink.tv` serves the MP4 with no headers at all and honours `Range`, which is why
 *     the MP4 is the primary stream — see [MovieLinkPd2.choose] for the full reasoning.
 *
 * There is deliberately no referer allowlist rotation here: unlike megaplay, the *embed page* is
 * not gated by Referer, and the only host that checks one (`watch.movielink.tv`) checks that it is
 * a movielinkbd domain, which [watchReferer] already is.
 *
 * Nothing is retried. A 403 here is Cloudflare rejecting the app fingerprint, not a stale token, so
 * replaying the same request only costs time; the caller's server ordering moves on instead.
 */
class MovieLinkStreamResolver(
    private val client: OkHttpClient,
    watchReferer: String,
    private val userAgent: String = HttpClientFactory.UA_DESKTOP,
) : StreamResolver {

    private val referer: String = watchReferer.trim()

    /**
     * The resolved URLs carry no short-lived token on this host (measured: the same `fly`/`watch`
     * paths answer for hours), so the shared 15-minute [ResolveCache] is only here to save one page
     * fetch per replay — nothing goes stale in a way that would break playback.
     */
    private val cache: ResolveCache = ResolveCache.shared

    override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> =
        withContext(Dispatchers.IO) {
            val url = embedUrl.trim()
            val parsed = url.toHttpUrlOrNull()
            if (url.isEmpty() || parsed == null) {
                return@withContext Result.failure(
                    ResolveException.Parse("embed url is not absolute: '$embedUrl'"),
                )
            }
            cache.get(url, audio)?.let { return@withContext Result.success(it) }

            try {
                val stream = attempt(parsed, audio)
                cache.put(url, audio, stream)
                Result.success(stream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResolveException) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(ResolveException.Network(e))
            } catch (e: Exception) {
                // Anything else is a shape change on the page, not a transport failure.
                Result.failure(ResolveException.Parse("${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
            }
        }

    private suspend fun attempt(embedUrl: HttpUrl, audio: AudioTrack): PlayableStream {
        val html = fetchShell(embedUrl)

        val payload = MovieLinkPd2.extract(html)
            ?: throw ResolveException.Parse("no __PD2 config in the embed page")

        // 1-based and optional: without it the page's own default entry is played.
        val episodeNumber = embedUrl.queryParameter(EPISODE_PARAM)?.toIntOrNull()
        val choice = MovieLinkPd2.choose(payload, episodeNumber)
            ?: throw ResolveException.Parse("__PD2 lists no playable url")

        return PlayableStream(
            masterUrl = choice.url,
            // The MP4 host ignores Referer, but the HLS host requires a movielinkbd one, and the
            // media source sends the same header map to every hop — so one referer covers both.
            referer = referer.ifEmpty { originOf(embedUrl) },
            userAgent = userAgent,
            audio = audio,
            container = if (choice.hls) StreamContainer.HLS else StreamContainer.PROGRESSIVE,
        )
    }

    private suspend fun fetchShell(embedUrl: HttpUrl): String {
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", userAgent)
            .header("Referer", referer.ifEmpty { originOf(embedUrl) })
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val call = client.newCall(request)
        // The read can outlive the viewer's interest; hang the socket up with the coroutine.
        call.cancelOnCancellation()

        var html = ""
        call.execute().use { response ->
            MovieLinkErrors.forStatus(response.code)?.let { throw it }

            // The shell is ~13 KB. A page two orders of magnitude larger is not this provider and
            // is not worth buffering into a String.
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_BODY_BYTES) {
                throw ResolveException.Parse("embed page too large ($length bytes)")
            }
            html = response.body?.string().orEmpty()
        }
        if (html.isEmpty()) throw ResolveException.Parse("embed page is empty")
        return html
    }

    /** A missing/blank `watchReferer` still needs a movielinkbd referer: derive it from the page. */
    private fun originOf(url: HttpUrl): String = "${url.scheme}://${url.host}/"

    private companion object {
        /** Query parameter the caller can use to pick an episode of a series payload. */
        const val EPISODE_PARAM = "ep"
        const val MAX_BODY_BYTES = 512L * 1024L
    }
}

/**
 * HTTP status -> failure, kept pure so the whole mapping is testable without a socket.
 *
 * 403 is the Cloudflare answer to a non-browser fingerprint and, on `watch.movielink.tv`, to a
 * foreign Referer; 404/410 are definitive (a token that no longer exists), so the caller must not
 * re-try them against another server. Everything else is transport and surfaces as [IOException]
 * so the resolver reports it as [ResolveException.Network].
 */
internal object MovieLinkErrors {

    fun forStatus(code: Int): Throwable? = when {
        code in 200..299 -> null
        code == 403 -> ResolveException.Blocked("embed page 403")
        code == 404 -> ResolveException.NotFound()
        code == 410 -> ResolveException.Dead()
        else -> IOException("embed page HTTP $code")
    }
}
