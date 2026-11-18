package com.animo.app.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The slice of HLS a downloader needs, pure JVM. Media3 parses playlists for playback; a download
 * has to walk them itself, because it writes the segments into one file that any player can open.
 *
 * Measured on every source the app plays (research/EXTRACTOR_SPECS.md §5): MPEG-TS segments, no
 * keys, no init section. AES-128 and `#EXT-X-MAP` are still handled, so a source that adds them
 * keeps downloading instead of producing a broken file.
 */
object HlsPlaylist {

    data class Variant(val uri: String, val height: Int, val bandwidth: Long, val audioGroup: String?)

    data class Rendition(val type: String, val groupId: String, val uri: String?)

    data class Master(val variants: List<Variant>, val renditions: List<Rendition>)

    data class Key(val method: String, val uri: String?, val iv: ByteArray?)

    data class Segment(
        val uri: String,
        val sequence: Long,
        val key: Key?,
        /** Inclusive byte range inside [uri], or `null` for the whole resource. */
        val byteRange: LongRange? = null,
    )

    data class Media(val segments: List<Segment>, val initUri: String?, val initRange: LongRange?)

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, baseUrl: String): Master {
        val variants = mutableListOf<Variant>()
        val renditions = mutableListOf<Rendition>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    renditions += Rendition(
                        type = attrs["TYPE"].orEmpty(),
                        groupId = attrs["GROUP-ID"].orEmpty(),
                        uri = attrs["URI"]?.let { resolve(baseUrl, it) },
                    )
                }
                line.startsWith("#") -> Unit
                pending != null -> {
                    val attrs = pending
                    variants += Variant(
                        uri = resolve(baseUrl, line),
                        height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0,
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        audioGroup = attrs["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return Master(variants, renditions)
    }

    fun parseMedia(text: String, baseUrl: String): Media {
        val segments = mutableListOf<Segment>()
        var sequence = 0L
        var key: Key? = null
        var initUri: String? = null
        var initRange: LongRange? = null
        var nextRange: LongRange? = null
        var lastRangeEnd = -1L
        var inSegment = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val method = attrs["METHOD"].orEmpty().uppercase()
                    key = if (method == "NONE" || method.isEmpty()) {
                        null
                    } else {
                        Key(method, attrs["URI"]?.let { resolve(baseUrl, it) }, attrs["IV"]?.let(::hexIv))
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    initUri = attrs["URI"]?.let { resolve(baseUrl, it) }
                    initRange = attrs["BYTERANGE"]?.let { byteRange(it, 0L) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    nextRange = byteRange(line.substringAfter(':'), lastRangeEnd + 1)
                }
                line.startsWith("#EXTINF") -> inSegment = true
                line.startsWith("#") -> Unit
                inSegment -> {
                    segments += Segment(resolve(baseUrl, line), sequence, key, nextRange)
                    nextRange?.let { lastRangeEnd = it.last }
                    nextRange = null
                    sequence++
                    inSegment = false
                }
            }
        }
        return Media(segments, initUri, initRange)
    }

    /** `RESOLUTION=1280x720,CODECS="a,b",URI="x"` — values may be quoted and contain commas. */
    fun attributes(list: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < list.length) {
            val eq = list.indexOf('=', index).takeIf { it >= 0 } ?: break
            val name = list.substring(index, eq).trim().trimStart(',').trim()
            var valueStart = eq + 1
            val value: String
            if (valueStart < list.length && list[valueStart] == '"') {
                valueStart++
                val close = list.indexOf('"', valueStart).takeIf { it >= 0 } ?: list.length
                value = list.substring(valueStart, close)
                index = (list.indexOf(',', close).takeIf { it >= 0 } ?: list.length) + 1
            } else {
                val comma = list.indexOf(',', valueStart).takeIf { it >= 0 } ?: list.length
                value = list.substring(valueStart, comma).trim()
                index = comma + 1
            }
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** `length[@offset]`; without an offset the range continues from the previous one. */
    private fun byteRange(spec: String, defaultOffset: Long): LongRange? {
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: return null
        val offset = spec.substringAfter('@', "").trim().toLongOrNull() ?: defaultOffset
        return offset until offset + length
    }

    private fun hexIv(value: String): ByteArray? {
        val hex = value.removePrefix("0x").removePrefix("0X")
        if (hex.length != 32) return null
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** The IV HLS implies when a key names none: the media sequence number, big-endian. */
    fun sequenceIv(sequence: Long): ByteArray = ByteArray(16).also { iv ->
        for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
    }

    fun resolve(base: String, reference: String): String =
        base.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: reference
}
