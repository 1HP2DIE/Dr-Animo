package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * megaplay resolver: embed page -> `getSources` -> AES-256-CBC -> `master.m3u8`.
 *
 * Every hop is mirrored from research/RESOLVER_SPEC.md §1 (live-measured, 15/15 embeds). In
 * particular:
 *  - `Referer` is the only CDN gate; `?token=` is neither required nor sufficient, so there is no
 *    URL signing here (RESOLVER_SPEC §1 header matrix).
 *  - A soft-410 page (HTTP 200 + "Error Code: 410") means the referer was rejected, so each
 *    allowlisted referer is tried before the episode is declared [ResolveException.Dead].
 *  - The 404 page is definitive (e.g. a synthesized `/dub`), so it is never retried.
 */
class MegaplayStreamResolver(
    private val client: OkHttpClient,
    private val cache: ResolveCache = ResolveCache.shared,
    private val userAgent: String = Media3Factory.DEFAULT_USER_AGENT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamResolver {

    private val refererCursor = AtomicInteger(0)

    /** The only referer the stream CDN accepts (verified on device and via curl). */
    private val cdnReferer: String get() = ProviderVault.url(ProviderKey.STREAM_REFERER)

    override suspend fun resolve(embedUrl: String, audio: AudioTrack): Result<PlayableStream> =
        withContext(dispatcher) {
            val url = embedUrl.trim()
            if (url.isEmpty()) {
                return@withContext Result.failure(ResolveException.Parse("blank embed url"))
            }
            cache.get(url, audio)?.let { return@withContext Result.success(it) }

            var last: ResolveException = ResolveException.Parse("no referer candidates")
            val tried = LinkedHashSet<String>()

            // Rotate the starting referer so a 403-triggered re-resolve does not replay the same one.
            for (referer in rotatedReferers()) {
                tried.add(referer)
                when (val outcome = runAttempt(url, referer, audio)) {
                    is Attempt.Ok -> {
                        cache.put(url, audio, outcome.stream)
                        return@withContext Result.success(outcome.stream)
                    }
                    is Attempt.Fatal -> return@withContext Result.failure(outcome.error)
                    is Attempt.Retry -> last = outcome.error
                }
            }

            // RESOLVER_SPEC §5: allowlist TTL is 6 h and domains rotate, so one forced refresh is a
            // cheap last resort. A failed refresh is swallowed — playback must never block on it.
            val refreshed = try {
                Referers.refreshFromDomains(client, force = true)
            } catch (e: Exception) {
                emptyList()
            }
            for (referer in refreshed.filterNot { it in tried }) {
                when (val outcome = runAttempt(url, referer, audio)) {
                    is Attempt.Ok -> {
                        cache.put(url, audio, outcome.stream)
                        return@withContext Result.success(outcome.stream)
                    }
                    is Attempt.Fatal -> return@withContext Result.failure(outcome.error)
                    is Attempt.Retry -> last = outcome.error
                }
            }
            Result.failure(last)
        }

    /** Returns the referer candidates starting at a rotating offset. */
    private fun rotatedReferers(): List<String> {
        val available = Referers.candidates
        if (available.isEmpty()) return emptyList()
        val start = Math.floorMod(refererCursor.getAndIncrement(), available.size)
        return List(available.size) { offset -> available[(start + offset) % available.size] }
    }

    private sealed interface Attempt {
        data class Ok(val stream: PlayableStream) : Attempt
        data class Retry(val error: ResolveException) : Attempt
        data class Fatal(val error: ResolveException) : Attempt
    }

    private fun runAttempt(embedUrl: String, referer: String, audio: AudioTrack): Attempt =
        try {
            Attempt.Ok(attempt(embedUrl, referer, audio))
        } catch (e: ResolveException.NotFound) {
            Attempt.Fatal(e)
        } catch (e: ResolveException) {
            Attempt.Retry(e)
        } catch (e: IOException) {
            Attempt.Retry(ResolveException.Network(e))
        } catch (e: Exception) {
            Attempt.Retry(ResolveException.Parse("${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
        }

    private fun attempt(embedUrl: String, referer: String, audio: AudioTrack): PlayableStream {
        val page = get(embedUrl, referer)
        when {
            page.code == 403 -> throw ResolveException.Blocked("embed page 403")
            page.code == 404 -> throw ResolveException.NotFound()
            page.code !in 200..299 -> throw IOException("embed page HTTP ${page.code}")
        }

        val html = page.body
        if (html.contains(MARKER_404) || html.contains(MARKER_MOVED)) throw ResolveException.NotFound()
        // RESOLVER_SPEC §1 hard rule 1: 410 here means "referer not allowlisted", not "deleted".
        if (html.contains(MARKER_410)) throw ResolveException.Dead()

        val fileId = MegaplayParser.fileId(html)
            ?: throw if (MegaplayParser.hasPlayerDiv(html)) {
                ResolveException.Parse("player element present but data-id missing")
            } else {
                ResolveException.Dead("player div absent after referer retry")
            }

        val sources = fetchSources(fileId, referer)
        when {
            sources.code == 403 -> throw ResolveException.Blocked("getSources 403")
            // 404 from getSources means we sent the wrong id (embedId instead of data-id) — a parse bug.
            sources.code == 404 -> throw ResolveException.Parse("getSources 404: id rejected")
            sources.code !in 200..299 -> throw IOException("getSources HTTP ${sources.code}")
        }

        val masterUrl = MegaplayParser.masterUrl(sources.body)
            ?: throw ResolveException.Parse("no playable file in getSources")

        val tracks = StreamTracks.parse(sources.body)
        return PlayableStream(
            masterUrl = masterUrl,
            // Device-verified 2026-11-17: the CDN host (fetch.*) accepts ONLY the megaplay referer —
            // anikototv.bz / anikoto.cz return 403 there (they only pass the *embed page* allowlist).
            // So the page referer is used for hops 1-2 and this fixed one for playback.
            referer = cdnReferer,
            userAgent = userAgent,
            audio = audio,
            thumbnails = JsonLite.stringField(sources.body, "image"),
            intro = MegaplayParser.markerMs(sources.body, "intro"),
            outro = MegaplayParser.markerMs(sources.body, "outro"),
            // Subtitle and preview hosts gate on the same referer as the CDN (measured: 403 without).
            subtitles = tracks.subtitles,
            previewVtt = tracks.previewVtt,
        )
    }

    /**
     * Tries `getSources` first and `getSourcesNew` as a fallback (EXTRACTOR_SPECS §7 trap 3).
     * `X-Requested-With` is mandatory — without it the endpoint answers 403.
     */
    private fun fetchSources(fileId: String, referer: String): Resp {
        val stamp = System.currentTimeMillis() / 1000L
        var last = Resp(0, "")
        for (path in SOURCE_PATHS) {
            val host = ProviderVault.url(ProviderKey.STREAM_HOST)
            val url = "$host/stream/$path?id=$fileId&platform=OTHER&st=other&_=$stamp"
            val response = get(url, referer, xhr = true)
            last = response
            if (response.code == 403 || response.code == 404) return response
            val usable = response.code in 200..299 &&
                (response.body.contains("\"enc\"") || response.body.contains("\"file\""))
            if (usable) return response
        }
        return last
    }

    private fun get(url: String, referer: String, xhr: Boolean = false): Resp {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
        if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
        return client.newCall(builder.build()).execute().use { response ->
            Resp(response.code, response.body?.string().orEmpty())
        }
    }

    private data class Resp(val code: Int, val body: String)

    companion object {
        private const val MARKER_404 = "Error Code: 404"
        private const val MARKER_410 = "Error Code: 410"
        private const val MARKER_MOVED = "doesn't exist or has been moved"
        private val SOURCE_PATHS = listOf("getSources", "getSourcesNew")
    }
}

/**
 * HTML/JSON parsing for the megaplay hops, kept separate so it stays pure and unit-testable.
 * The attribute regex is deliberately lowercase-tolerant: an uppercase-only HLS-attribute pattern
 * silently misses `data-id` (EXTRACTOR_SPECS §7 trap 2 — this bug already shipped once).
 */
internal object MegaplayParser {

    private val DATA_ID =
        Regex("""data-id\s*=\s*["']([A-Za-z0-9_-]+)["']""", RegexOption.IGNORE_CASE)
    /** The player element id is provider-specific, so it comes from the vault, not a literal. */
    private val PLAYER_DIV: Regex by lazy {
        val marker = Regex.escape(ProviderVault.text(ProviderKey.PLAYER_MARKER))
        Regex("id\\s*=\\s*[\"']$marker[\"']", RegexOption.IGNORE_CASE)
    }
    private val START = Regex("""["]start["]\s*:\s*(-?[0-9]+(?:\.[0-9]+)?)""")
    private val END = Regex("""["]end["]\s*:\s*(-?[0-9]+(?:\.[0-9]+)?)""")

    fun fileId(html: String): String? = DATA_ID.find(html)?.groupValues?.getOrNull(1)

    fun hasPlayerDiv(html: String): Boolean = PLAYER_DIV.containsMatchIn(html)

    /**
     * `enc` (AES payload) is authoritative; `sources.file` / `file` are the alternate shape some
     * responses use (EXTRACTOR_SPECS §1). Only the decrypted payload is trusted to be any URL.
     */
    fun masterUrl(sourcesJson: String): String? {
        val enc = JsonLite.stringField(sourcesJson, "enc")
        if (enc != null) {
            val decrypted = try {
                MegaplayAes.decrypt(enc)
            } catch (e: Exception) {
                throw ResolveException.Parse("AES: ${e.javaClass.simpleName}")
            }
            JsonLite.stringField(decrypted, "file")?.let { file ->
                if (isHttpUrl(file)) return file
            }
        }

        val sourcesAt = sourcesJson.indexOf("\"sources\"")
        if (sourcesAt >= 0) {
            JsonLite.stringField(sourcesJson, "file", fromIndex = sourcesAt)
                ?.let { if (isPlaylistUrl(it)) return it }
        }
        // Last resort: any "file" that actually looks like a playlist (subtitle files never do).
        JsonLite.stringField(sourcesJson, "file")
            ?.let { if (isPlaylistUrl(it)) return it }
        return null
    }

    /**
     * `intro`/`outro` markers arrive in seconds; [LongRange] is milliseconds like every other
     * position in the app.
     */
    fun markerMs(json: String, name: String): LongRange? {
        val fieldAt = JsonLite.indexKey(json, name)
        if (fieldAt < 0) return null
        val start = START.find(json, fieldAt)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        val end = END.find(json, fieldAt)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (end <= start) return null
        val startMs = toMillis(start)
        val endMs = toMillis(end)
        if (endMs <= startMs) return null
        return startMs until endMs
    }

    private fun toMillis(value: Double): Long =
        if (value in 0.0..86_400.0) (value * 1000).toLong() else value.toLong()

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    private fun isPlaylistUrl(value: String): Boolean =
        isHttpUrl(value) && (value.contains(".m3u8") || value.contains(".mpd"))
}

/**
 * Minimal JSON string/number reader for the two fixed megaplay payloads. Avoids a serialization
 * dependency on the resolver's hot path and tolerates the response shapes measured in
 * EXTRACTOR_SPECS §1 (`{"enc":...}` and `{"sources":{"file":...}}`).
 */
internal object JsonLite {

    fun indexKey(json: String, name: String): Int = json.indexOf("\"$name\"")

    fun stringField(json: String, name: String, fromIndex: Int = 0): String? {
        val key = "\"$name\""
        var searchFrom = fromIndex.coerceAtLeast(0)
        while (true) {
            val keyAt = json.indexOf(key, searchFrom)
            if (keyAt < 0) return null
            var cursor = keyAt + key.length
            while (cursor < json.length && json[cursor].isWhitespace()) cursor++
            if (cursor < json.length && json[cursor] == ':') {
                cursor++
                while (cursor < json.length && json[cursor].isWhitespace()) cursor++
                if (cursor < json.length && json[cursor] == '"') return readString(json, cursor)
            }
            searchFrom = keyAt + key.length
        }
    }

    fun numberField(json: String, name: String, fromIndex: Int = 0): Double? {
        val pattern = Regex("""["]$name["]\s*:\s*(-?[0-9]+(?:\.[0-9]+)?)""")
        return pattern.find(json, fromIndex.coerceAtLeast(0))?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun readString(json: String, quoteAt: Int): String {
        val out = StringBuilder()
        var cursor = quoteAt + 1
        while (cursor < json.length) {
            val char = json[cursor]
            when {
                char == '"' -> return out.toString()
                char == '\\' && cursor + 1 < json.length -> {
                    when (val escaped = json[cursor + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            val hex = json.substring(cursor + 2, (cursor + 6).coerceAtMost(json.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                cursor += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                    cursor += 2
                }
                else -> {
                    out.append(char)
                    cursor++
                }
            }
        }
        return out.toString()
    }
}

/**
 * AES-256-CBC / PKCS7 payload decrypt (research/RESOLVER_SPEC.md §1 hop 3).
 * Uses `android.util.Base64` because `java.util.Base64` is API 26+ and minSdk is 24.
 */
internal object MegaplayAes {

    private val keyBytes: ByteArray by lazy {
        ProviderVault.text(ProviderKey.EMBED_AES_KEY).toByteArray(Charsets.UTF_8).copyOf(32)
    }
    private val ivBytes: ByteArray by lazy {
        ProviderVault.text(ProviderKey.EMBED_AES_IV).toByteArray(Charsets.UTF_8)
    }

    fun decrypt(encoded: String): String {
        require(encoded.isNotEmpty()) { "empty AES payload" }
        require(ivBytes.size == 16) { "IV must be 16 bytes" }

        val normalized = encoded
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // PKCS5 == PKCS7 for AES
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val decoded = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
        return String(cipher.doFinal(decoded), Charsets.UTF_8).trim('\u0000')
    }
}
