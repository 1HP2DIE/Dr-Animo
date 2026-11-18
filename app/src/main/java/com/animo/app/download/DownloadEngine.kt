package com.animo.app.download

import com.animo.app.AppLog
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.StreamSubtitle
import com.animo.app.player.Media3Factory
import com.animo.app.subtitle.SubtitleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadException(message: String) : IOException(message)

/** A quality the viewer can pick before downloading. `height == 0` = the source names none. */
data class QualityOption(val label: String, val height: Int)

/**
 * Turns a resolved [PlayableStream] into **one ordinary video file**:
 *  - a progressive source (MP4/MKV) is copied as-is, resumably;
 *  - an HLS source has its segments fetched in parallel and joined in order. MPEG-TS segments
 *    concatenate into a valid `.ts`; fMP4 segments behind their init section into a valid `.mp4`.
 *
 * One file is the point: VLC, MX Player and this app's own player all open it, which a Media3
 * download cache (chunks in a private database) would not allow.
 */
class DownloadEngine(client: OkHttpClient) {

    /**
     * The shared client caps a *whole call* at 45 s, which would abort any episode whose body takes
     * longer to arrive. A download is one long body read, so the call timeout goes and the read
     * timeout stays: a stalled socket is still caught, and a slow-but-moving one is not killed.
     */
    private val client = client.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class Result(val file: File, val qualityLabel: String, val subtitle: File?)

    private sealed class Source(val label: String) {
        class Progressive(val url: String, label: String) : Source(label)
        class Hls(val mediaUrl: String, label: String) : Source(label)
    }

    /** Qualities on offer for [stream], highest first. Never empty: a single file is "Original". */
    suspend fun options(stream: PlayableStream): List<QualityOption> = withContext(Dispatchers.IO) {
        if (stream.alternates.isNotEmpty()) {
            return@withContext stream.alternates
                .sortedByDescending { it.height }
                .map { QualityOption(it.label.ifBlank { "${it.height}p" }, it.height) }
        }
        if (stream.container == StreamContainer.PROGRESSIVE) return@withContext listOf(ORIGINAL)
        val master = fetchText(stream.masterUrl, stream)
        if (!HlsPlaylist.isMaster(master.body)) return@withContext listOf(ORIGINAL)
        HlsPlaylist.parseMaster(master.body, master.url).variants
            .filter { it.height > 0 }
            .distinctBy { it.height }
            .sortedByDescending { it.height }
            .map { QualityOption("${it.height}p", it.height) }
            .ifEmpty { listOf(ORIGINAL) }
    }

    suspend fun download(
        stream: PlayableStream,
        targetHeight: Int,
        directory: File,
        baseName: (qualityLabel: String) -> String,
        /** Subtitle track to save: `null` = automatic pick, empty = none. */
        subtitleLabel: String? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long, fraction: Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val source = pickSource(stream, targetHeight)
        val name = sanitize(baseName(source.label))
        val video = when (source) {
            is Source.Progressive -> downloadProgressive(source.url, stream, directory, name, onProgress)
            is Source.Hls -> downloadHls(source.mediaUrl, stream, directory, name, onProgress)
        }
        val subtitle = runCatching { downloadSubtitle(stream, directory, name, subtitleLabel) }
            .onFailure { AppLog.w("download subtitle failed", it) }
            .getOrNull()
        Result(video, source.label, subtitle)
    }

    // ---------------------------------------------------------------- choosing

    private suspend fun pickSource(stream: PlayableStream, target: Int): Source {
        val progressive = stream.container == StreamContainer.PROGRESSIVE
        if (stream.alternates.isNotEmpty()) {
            val alternate = pickByHeight(stream.alternates, target) { it.height }!!
            val label = alternate.label.ifBlank { "${alternate.height}p" }
            return if (progressive) Source.Progressive(alternate.url, label) else hlsSource(alternate.url, stream, target, label)
        }
        if (progressive) return Source.Progressive(stream.masterUrl, ORIGINAL.label)
        return hlsSource(stream.masterUrl, stream, target, ORIGINAL.label)
    }

    private suspend fun hlsSource(url: String, stream: PlayableStream, target: Int, fallbackLabel: String): Source {
        val text = fetchText(url, stream)
        if (!HlsPlaylist.isMaster(text.body)) return Source.Hls(text.url, fallbackLabel)
        val master = HlsPlaylist.parseMaster(text.body, text.url)
        // Height 0 means the playlist never said; in a real ladder that is the audio-only rendition,
        // and picking it would save an episode with no picture.
        val video = master.variants.filter { it.height > 0 }.ifEmpty { master.variants }
        val variant = pickByHeight(video, target) { it.height }
            ?: throw DownloadException("the playlist lists no video")
        // A variant whose audio is a separate playlist cannot become one file without muxing.
        val separateAudio = variant.audioGroup?.let { group ->
            master.renditions.any { it.type == "AUDIO" && it.groupId == group && it.uri != null }
        } == true
        if (separateAudio) throw DownloadException("this server keeps the audio apart from the video; try another server")
        return Source.Hls(variant.uri, if (variant.height > 0) "${variant.height}p" else fallbackLabel)
    }

    // ---------------------------------------------------------------- progressive

    private suspend fun downloadProgressive(
        url: String,
        stream: PlayableStream,
        directory: File,
        name: String,
        onProgress: suspend (Long, Long, Float) -> Unit,
    ): File {
        // One part name per strategy: a lazy chunked file is pre-sized to its full length, so
        // handing it to the resumable path would look like a finished download.
        val resumablePart = File(directory, "$name.stream.part")
        // Many file hosts cap each connection (Server 4: ~30 KB/s), so a large file is fetched as
        // ranges over several connections at once when the host allows ranges.
        val total = rangeTotal(url, stream)
        if (total != null && total > CHUNK_SIZE * 2) {
            val part = File(directory, "$name.chunked.part")
            downloadChunked(url, stream, part, total, onProgress)
            val extension = if (isMatroska(part)) "mkv" else "mp4"
            return finish(part, File(directory, "$name.$extension"))
        }
        var attempt = 0
        while (true) {
            try {
                copyResumable(url, stream, resumablePart, onProgress)
                break
            } catch (error: IOException) {
                if (error is DownloadException || ++attempt >= RETRIES) throw error
                AppLog.w("download interrupted, retrying ($attempt)", error)
                delay(1_500L * attempt)
            }
        }
        val extension = if (isMatroska(resumablePart)) "mkv" else "mp4"
        return finish(resumablePart, File(directory, "$name.$extension"))
    }

    /** Total size when the host answers a one-byte range with 206, else `null` (no ranges). */
    private fun rangeTotal(url: String, stream: PlayableStream): Long? = runCatching {
        client.newCall(request(url, stream) { header("Range", "bytes=0-0") }).execute().use { response ->
            if (response.code != 206) return@use null
            response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
        }
    }.getOrNull()

    /**
     * Fixed-size ranges, [PARALLEL_RANGES] at a time, written in place into a pre-sized file. The
     * finished range numbers go to a side file, so a stopped download resumes where it was.
     */
    private suspend fun downloadChunked(
        url: String,
        stream: PlayableStream,
        part: File,
        total: Long,
        onProgress: suspend (Long, Long, Float) -> Unit,
    ) = coroutineScope {
        // The range size is in the name: a ledger written with another size must not be reused.
        val ledger = File(part.path + ".ranges$CHUNK_SIZE")
        File(part.parentFile, part.name + ".ranges").delete()
        val count = ((total + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val finished = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        if (ledger.exists() && part.exists() && part.length() == total) {
            ledger.readLines().mapNotNullTo(finished) { it.trim().toIntOrNull() }
        } else {
            ledger.delete()
            part.delete()
        }
        val bytes = AtomicLong(finished.sumOf { chunkLength(it, total) })
        java.io.RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val reporter = launch {
                while (isActive) {
                    onProgress(bytes.get(), total, bytes.get().toFloat() / total)
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            val gate = Semaphore(PARALLEL_RANGES)
            (0 until count).filterNot { it in finished }.map { index ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val start = index * CHUNK_SIZE
                        val end = minOf(total, start + CHUNK_SIZE) - 1
                        // Bytes count as they arrive (smooth progress); a failed attempt takes its
                        // partial count back before retrying.
                        val data = fetchRange(url, stream, start, end) { delta -> bytes.addAndGet(delta) }
                        synchronized(file) {
                            file.seek(start)
                            file.write(data)
                        }
                        synchronized(ledger) { ledger.appendText("$index\n") }
                        finished += index
                    }
                }
            }.awaitAll()
            reporter.cancel()
        }
        ledger.delete()
        onProgress(total, total, 1f)
    }

    private fun chunkLength(index: Int, total: Long): Long =
        minOf(total, (index + 1) * CHUNK_SIZE) - index * CHUNK_SIZE

    private suspend fun fetchRange(
        url: String,
        stream: PlayableStream,
        start: Long,
        end: Long,
        onBytes: (Long) -> Unit,
    ): ByteArray {
        var attempt = 0
        while (true) {
            var counted = 0L
            try {
                val data = client.newCall(request(url, stream) { header("Range", "bytes=$start-$end") })
                    .execute().use { response ->
                        check(response, url)
                        if (response.code != 206) throw IOException("range ignored (HTTP ${response.code})")
                        val body = response.body ?: throw IOException("empty body")
                        val out = java.io.ByteArrayOutputStream((end - start + 1).toInt())
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                counted += read
                                onBytes(read.toLong())
                            }
                        }
                        out.toByteArray()
                    }
                if (data.size.toLong() != end - start + 1) throw IOException("short range ${data.size}")
                return data
            } catch (error: IOException) {
                onBytes(-counted)
                // Slow hosts stall now and then; a range is cheap to ask for again.
                if (error is DownloadException || ++attempt >= RANGE_RETRIES) throw error
                delay(minOf(10_000L, 1_000L * attempt))
            }
        }
    }

    private suspend fun copyResumable(
        url: String,
        stream: PlayableStream,
        part: File,
        onProgress: suspend (Long, Long, Float) -> Unit,
    ) {
        val offset = if (part.exists()) part.length() else 0L
        val request = request(url, stream) { if (offset > 0) header("Range", "bytes=$offset-") }
        client.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0) {
                // "416" only means the bytes are already here when the server names that same total.
                // Anything else (a leftover part from another strategy, a changed encode) is junk.
                val total = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                if (total != null && total == offset) return
                part.delete()
                throw IOException("resume point rejected (HTTP 416 at $offset bytes)")
            }
            check(response, url)
            val resumed = response.code == 206 && offset > 0
            if (resumed) {
                val start = response.header("Content-Range")
                    ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (start != null && start != offset) {
                    part.delete()
                    throw IOException("resume answered at byte $start, not $offset")
                }
            }
            val body = response.body ?: throw IOException("empty body")
            val total = (if (resumed) offset else 0L) + body.contentLength().coerceAtLeast(0L)
            var done = if (resumed) offset else 0L
            var lastReport = 0L
            FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(done, total, if (total > 0) done.toFloat() / total else 0f)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("short body ($done of $total bytes)")
            onProgress(done, total.coerceAtLeast(done), 1f)
        }
    }

    // ---------------------------------------------------------------- HLS

    private suspend fun downloadHls(
        mediaUrl: String,
        stream: PlayableStream,
        directory: File,
        name: String,
        onProgress: suspend (Long, Long, Float) -> Unit,
    ): File = coroutineScope {
        val mediaText = fetchText(mediaUrl, stream)
        val media = HlsPlaylist.parseMedia(mediaText.body, mediaText.url)
        if (media.segments.isEmpty()) throw DownloadException("the playlist has no segments")
        val parts = File(directory, ".$name.parts").apply { mkdirs() }
        val keys = ConcurrentHashMap<String, ByteArray>()

        val total = media.segments.size
        val finished = AtomicInteger(0)
        val bytes = AtomicLong(0L)
        media.segments.indices.forEach { index ->
            val file = File(parts, "$index.seg")
            if (file.exists()) {
                finished.incrementAndGet()
                bytes.addAndGet(file.length())
            }
        }

        val reporter = launch {
            while (isActive) {
                val count = finished.get()
                val estimate = if (count > 0) bytes.get() / count * total else 0L
                onProgress(bytes.get(), estimate, count.toFloat() / total)
                delay(PROGRESS_INTERVAL_MS)
            }
        }

        val gate = Semaphore(PARALLEL_SEGMENTS)
        media.segments.mapIndexed { index, segment ->
            async(Dispatchers.IO) {
                val file = File(parts, "$index.seg")
                if (file.exists()) return@async
                gate.withPermit {
                    val data = fetchSegment(segment, stream, keys)
                    val tmp = File(parts, "$index.tmp")
                    tmp.writeBytes(data)
                    if (!tmp.renameTo(file)) throw IOException("could not stage segment $index")
                    bytes.addAndGet(data.size.toLong())
                    finished.incrementAndGet()
                }
            }
        }.awaitAll()
        reporter.cancel()

        val init = media.initUri?.let { fetchBytes(it, stream, media.initRange) }
        val part = File(directory, "$name.hls.part")
        FileOutputStream(part).use { out ->
            init?.let(out::write)
            for (index in media.segments.indices) {
                currentCoroutineContext().ensureActive()
                File(parts, "$index.seg").inputStream().use { it.copyTo(out, BUFFER) }
            }
        }
        parts.deleteRecursively()
        onProgress(part.length(), part.length(), 1f)
        finish(part, File(directory, "$name.${if (init != null) "mp4" else "ts"}"))
    }

    private suspend fun fetchSegment(
        segment: HlsPlaylist.Segment,
        stream: PlayableStream,
        keys: ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val raw = fetchBytes(segment.uri, stream, segment.byteRange)
                val key = segment.key ?: return raw
                if (key.method != "AES-128" || key.uri == null) {
                    throw DownloadException("this stream is protected (${key.method}) and cannot be saved")
                }
                val secret = keys.getOrPut(key.uri) { fetchBytes(key.uri, stream, null) }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(secret, "AES"),
                    IvParameterSpec(key.iv ?: HlsPlaylist.sequenceIv(segment.sequence)),
                )
                return cipher.doFinal(raw)
            } catch (error: IOException) {
                if (error is DownloadException || ++attempt >= RETRIES) throw error
                delay(1_000L * attempt)
            }
        }
    }

    // ---------------------------------------------------------------- subtitles

    /**
     * One side-car subtitle next to the video, named like it so players that look for one find it:
     * English for the original audio, the forced (signs) track for a dub.
     */
    private suspend fun downloadSubtitle(stream: PlayableStream, directory: File, name: String, label: String?): File? {
        val track = when {
            label == null -> pickSubtitle(stream.subtitles, stream.audio)
            label.isEmpty() -> null
            else -> stream.subtitles.firstOrNull { it.label == label } ?: pickSubtitle(stream.subtitles, stream.audio)
        } ?: return null
        val file = SubtitleFiles.download(
            client = client,
            url = track.url,
            headers = mapOf("Referer" to stream.referer, "User-Agent" to stream.userAgent),
        )
        // The URL often lies (a `.php` download, or SRT served as `.vtt`); the content is what a
        // player keys on, so name the file after what is actually inside it.
        val head = file.content.take(4096)
        val extension = when {
            head.trimStart().startsWith("WEBVTT") -> "vtt"
            "[Script Info]" in head -> "ass"
            "-->" in head -> "srt"
            else -> file.name.substringAfterLast('.', "vtt").lowercase()
                .takeIf { it in setOf("vtt", "srt", "ass", "ssa") } ?: "vtt"
        }
        return File(directory, "$name.$extension").apply { writeText(file.content) }
    }

    /** The track the automatic pick would save, so the sheet can preselect it. */
    fun defaultSubtitle(stream: PlayableStream): String? = pickSubtitle(stream.subtitles, stream.audio)?.label

    private fun pickSubtitle(tracks: List<StreamSubtitle>, audio: AudioTrack): StreamSubtitle? {
        if (tracks.isEmpty()) return null
        fun english(track: StreamSubtitle) =
            track.language?.startsWith("en") == true || track.label.startsWith("English", ignoreCase = true)
        return if (audio == AudioTrack.DUB) {
            tracks.firstOrNull { it.forced && english(it) } ?: tracks.firstOrNull { it.forced }
        } else {
            val full = tracks.filterNot { it.forced }
            full.firstOrNull { english(it) && it.isDefault } ?: full.firstOrNull(::english) ?: full.firstOrNull()
        }
    }

    // ---------------------------------------------------------------- http

    private fun request(url: String, stream: PlayableStream, extra: Request.Builder.() -> Unit = {}): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", stream.userAgent.ifBlank { Media3Factory.DEFAULT_USER_AGENT })
            .apply {
                if (stream.referer.isNotBlank()) Media3Factory.headers(stream.referer).forEach { (k, v) -> header(k, v) }
                extra()
            }
            .build()

    private class Text(val body: String, val url: String)

    private fun fetchText(url: String, stream: PlayableStream): Text =
        client.newCall(request(url, stream)).execute().use { response ->
            check(response, url)
            // Relative segment URIs must resolve against where the playlist actually came from:
            // stream hosts redirect to a CDN, and the typed URL then points at the wrong path.
            Text(response.body?.string().orEmpty(), response.request.url.toString())
        }

    private fun fetchBytes(url: String, stream: PlayableStream, range: LongRange?): ByteArray =
        client.newCall(request(url, stream) { range?.let { header("Range", "bytes=${it.first}-${it.last}") } })
            .execute().use { response ->
                check(response, url)
                // A host that ignores Range answers 200 with the whole resource; taking it would
                // paste the entire file where one slice belongs.
                if (range != null && response.code != 206) {
                    throw DownloadException("the server ignored the range request (HTTP ${response.code})")
                }
                response.body?.bytes() ?: throw IOException("empty body")
            }

    private fun check(response: Response, url: String) {
        if (response.isSuccessful) return
        val host = url.substringAfter("://").substringBefore('/')
        if (response.code == 403 || response.code == 404 || response.code == 410) {
            throw DownloadException("the server refused the file (HTTP ${response.code} at $host)")
        }
        throw IOException("HTTP ${response.code} at $host")
    }

    // ---------------------------------------------------------------- files

    private fun finish(part: File, target: File): File {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
        return target
    }

    private fun isMatroska(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        }
    }.getOrDefault(false)

    companion object {
        val ORIGINAL = QualityOption("Original", 0)

        private const val RETRIES = 4
        private const val PARALLEL_SEGMENTS = 6
        private const val PARALLEL_RANGES = 8
        private const val CHUNK_SIZE = 1L * 1024 * 1024
        private const val RANGE_RETRIES = 10
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 700L

        /** Highest at or below [target] (unknown heights count as fitting), else the lowest above it. */
        fun <T> pickByHeight(items: List<T>, target: Int, height: (T) -> Int): T? {
            if (items.isEmpty()) return null
            val sorted = items.sortedByDescending(height)
            if (target == Int.MAX_VALUE) return sorted.first()
            return sorted.firstOrNull { height(it) <= target } ?: sorted.last()
        }

        /** Safe on every filesystem a phone might mount (FAT on SD cards included). */
        fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("""\s+"""), " ").trim().take(120)
    }
}
