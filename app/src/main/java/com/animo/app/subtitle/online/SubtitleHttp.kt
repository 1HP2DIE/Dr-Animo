package com.animo.app.subtitle.online

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The one HTTP surface the online subtitle sources use.
 *
 * Sites that publish subtitles for free tend to reject clients that look like libraries, so every
 * request carries a normal browser User-Agent; a Referer is passed only where the site's own
 * download links require one. All work happens on [Dispatchers.IO] — OkHttp's synchronous API is
 * fine on a dispatcher and needs no callback plumbing.
 */
internal object SubtitleHttp {

    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val MAX_BYTES = 6 * 1024 * 1024

    suspend fun text(
        client: OkHttpClient,
        url: String,
        referer: String? = null,
    ): String = String(bytes(client, url, referer), Charsets.UTF_8)

    suspend fun bytes(
        client: OkHttpClient,
        url: String,
        referer: String? = null,
        accept: String = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.8")
            .apply { referer?.let { header("Referer", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("empty body for $url")
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BYTES) throw IOException("response larger than $MAX_BYTES bytes")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }
}

/** Percent-encodes a search term for a query string without dragging in a URL builder. */
internal fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

/** Strips tags and decodes the five entities a subtitle listing actually uses. */
internal fun String.plainText(): String = this
    .replace(Regex("<[^>]*>"), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()

/** All `href` values in a document, in order. */
internal fun String.hrefs(): List<String> =
    Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(this)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .toList()

/** Absolute URL for a possibly relative link. */
internal fun absolute(base: String, link: String): String = when {
    link.startsWith("http://") || link.startsWith("https://") -> link
    link.startsWith("//") -> "https:$link"
    link.startsWith("/") -> {
        val root = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1).orEmpty()
        root + link
    }
    else -> base.substringBeforeLast('/') + "/" + link
}
