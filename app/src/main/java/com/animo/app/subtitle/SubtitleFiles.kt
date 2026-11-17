package com.animo.app.subtitle

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A subtitle file, already decoded to text. [name] is what the UI shows and what hints the parser. */
data class SubtitleFile(val name: String, val content: String)

/**
 * Reads subtitle text from a document picker [Uri] or an http(s) link.
 *
 * Everything happens off the main thread and every read is capped: a picker can hand back anything
 * (a 2 GB video, a ZIP), and the player must not try to hold that in memory. Decoding handles a BOM
 * (UTF-8 / UTF-16) because subtitle files in the wild are split between those two encodings and a
 * mangled accent is indistinguishable from a parser bug.
 */
object SubtitleFiles {

    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Local file from `ActivityResultContracts.OpenDocument`. */
    suspend fun read(context: Context, uri: Uri): SubtitleFile = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readCapped(MAX_BYTES) }
            ?: throw IllegalStateException("cannot open $uri")
        SubtitleFile(
            name = displayName(context, uri) ?: uri.lastPathSegment ?: "subtitles",
            content = decode(bytes),
        )
    }

    /** Direct link (`.srt`, `.vtt`, `.ass`). Redirects are followed by OkHttp. */
    suspend fun download(client: OkHttpClient, url: String): SubtitleFile = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.trim())
            .header("Accept", "text/plain, */*")
            .header("User-Agent", "Animo")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty body")
            val bytes = body.byteStream().readCapped(MAX_BYTES)
            val name = url.substringBefore('?').substringBefore('#').trimEnd('/')
                .substringAfterLast('/')
                .ifBlank { "subtitles" }
            SubtitleFile(name = name, content = decode(bytes))
        }
    }

    /** `null` when the provider does not expose a name (some cloud providers do not). */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(column)
            }
    }.getOrNull()

    /**
     * Most download endpoints return a ZIP (OpenSubtitles, Podnapisi) or a GZIP, not text. This
     * unwraps the container and hands back the subtitle file inside it, preferring a real subtitle
     * entry over any readme the archive also carries.
     */
    fun decodePayload(
        bytes: ByteArray,
        fallbackName: String,
        preferEpisode: Int? = null,
    ): SubtitleFile? {
        if (bytes.size > 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        ) {
            return unzipSubtitle(bytes, fallbackName, preferEpisode)
        }
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            val raw = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readCapped(MAX_BYTES) }
            }.getOrNull() ?: return null
            return SubtitleFile(namedForText(fallbackName), decode(raw))
        }
        if (bytes.isEmpty()) return null
        return SubtitleFile(namedForText(fallbackName), decode(bytes))
    }

    private fun unzipSubtitle(
        bytes: ByteArray,
        fallbackName: String,
        preferEpisode: Int?,
    ): SubtitleFile? {
        var bestPayload: ByteArray? = null
        var bestName = fallbackName
        var bestScore = -1
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val entryName = entry.name.substringAfterLast('/')
                    val payload = zip.readCapped(MAX_BYTES)
                    // A subtitle entry always wins over a bigger readme, and the episode being
                    // watched wins over its neighbours inside a season pack.
                    val isSubtitle = isSubtitleFileName(entryName)
                    val score = payload.size +
                        if (isSubtitle) 1_000_000 else 0 +
                        if (isSubtitle && preferEpisode != null &&
                            episodeInName(entryName) == preferEpisode
                        ) {
                            10_000_000
                        } else {
                            0
                        }
                    if (score > bestScore) {
                        bestScore = score
                        bestPayload = payload
                        bestName = entryName
                    }
                    zip.closeEntry()
                }
            }
        }
        val payload = bestPayload ?: return null
        return SubtitleFile(namedForText(bestName), decode(payload))
    }

    private val SXXEXX = Regex("""(?i)\bS\d{1,2}E(\d{1,4})\b""")
    private val DASH_EPISODE = Regex("""\s-\s0*(\d{1,4})(?:[^0-9]|$)""")

    private fun episodeInName(name: String): Int? =
        SXXEXX.find(name)?.groupValues?.get(1)?.toIntOrNull()
            ?: DASH_EPISODE.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The parser sniffs content, but the UI shows the name — it should read like a subtitle file. */
    private fun namedForText(name: String): String =
        if (isSubtitleFileName(name)) name else "$name.srt"

    internal fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readCapped(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) error("subtitle file is larger than ${maxBytes / (1024 * 1024)} MB")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
