package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format of the multi-language embed source, verified against the live API on 2026-11-17.
 *
 * The response is plaintext JSON (no AES layer on this path), and the only identifier it needs is
 * an AniList id. Everything below is deliberately a *protocol* file: pure functions, no Android, so
 * the shard maths and the source selection are unit-testable.
 */
object AltStreamProtocol {

    /** FNV-1a, as the site's bundle computes it, over `"{type}-{id}-{season}-{episode}"`. */
    fun shardIndex(seed: String, shardCount: Int): Int {
        if (shardCount <= 0) return 0
        var hash = 2166136261u
        for (char in seed) {
            hash = hash xor char.code.toUInt()
            hash *= 16777619u
        }
        return (hash.toLong() and 0x7FFFFFFFL).rem(shardCount).toInt()
    }

    fun seed(type: String, id: String, season: Int, episode: Int): String = "$type-$id-$season-$episode"

    fun streamsUrl(
        shard: String,
        type: String,
        id: String,
        season: Int,
        episode: Int,
        audio: String?,
        timestampMs: Long,
    ): String = buildString {
        append(shard.trimEnd('/'))
        append("/api/core/streams?type=").append(type)
        append("&id=").append(id)
        append("&season=").append(season)
        append("&episode=").append(episode)
        if (!audio.isNullOrBlank()) append("&audio=").append(audio)
        append("&_t=").append(timestampMs)
    }

    /**
     * The season a catalogue title names, if it names one.
     *
     * The alternative server indexes one TMDB show and takes a **season number**; without it every
     * season request lands on season 1 (verified: season 1/2/3/4 of Dr. STONE return different
     * assets). Catalogue titles say it in several ways, so all of them are read here.
     */
    fun seasonFromTitle(titles: List<String>): Int? {
        for (title in titles) {
            SEASON_WORD.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            ORDINAL_SEASON.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            ROMAN_SEASON.find(title)?.let { match ->
                ROMAN[match.groupValues[1].lowercase()]?.let { return it }
            }
        }
        return null
    }

    private val SEASON_WORD = Regex("""(?i)\bseason\s*0*(\d{1,2})\b""")
    private val ORDINAL_SEASON = Regex("""(?i)\b0*(\d{1,2})(?:st|nd|rd|th)\s+season\b""")
    private val ROMAN_SEASON = Regex("""(?i)\bseason\s+(i{1,3}|iv|v)\b""")
    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5)

    /** The user's SUB/DUB switch, in this source's language vocabulary. */
    fun preferredAudio(audio: AudioTrack): String = when (audio) {
        // Anime ships Japanese-first; this source treats "ja" as the original.
        AudioTrack.SUB -> "ja"
        AudioTrack.DUB -> "hi"
    }

    fun parse(json: String): AltStreamPayload? = runCatching {
        lenient.decodeFromString<AltStreamPayload>(json)
    }.getOrNull()

    /**
     * The entry to play.
     *
     * Every language shares one media playlist, so there is nothing to gain from sorting by audio —
     * the `audio=` request parameter already hoisted the preferred language. What matters is
     * taking an HLS entry that the proxy can serve.
     */
    fun pick(payload: AltStreamPayload): AltStreamEntry? {
        val candidates = payload.sources.filter { it.type.equals("hls", ignoreCase = true) }
        val pool = candidates.ifEmpty { payload.sources }
        return pool.firstOrNull { it.proxyPath().isNotBlank() }
            ?: pool.firstOrNull { it.url.isNotBlank() }
    }

    /**
     * Playlist URL handed to Media3.
     *
     * It is the site's own proxy path, not the raw CDN URL: the raw segments are MPEG-TS wearing a
     * `.jpg`/`image/jpeg` costume (verified: 0x47 sync every 188 bytes), which Media3 routes to a
     * JPEG extractor and fails. The proxy re-serves the same bytes as `video/mp2t`.
     */
    fun playlistUrl(shard: String, source: AltStreamEntry): String {
        val path = source.proxyPath()
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return shard.trimEnd('/') + "/" + path.trimStart('/')
    }

    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }
}

@Serializable
data class AltStreamPayload(
    val sources: List<AltStreamEntry> = emptyList(),
    @SerialName("audioTracks") val audioTracks: List<AltStreamAudio> = emptyList(),
    val qualities: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    @SerialName("_diag") val diagnostics: AltStreamDiagnostics? = null,
)

@Serializable
data class AltStreamAudio(
    val language: String = "",
    val label: String = "",
    val index: Int = 0,
    val trackIndex: Int? = null,
)

@Serializable
data class AltStreamEntry(
    val id: String = "",
    val url: String = "",
    @SerialName("permanent_m3u8_url") val permanentUrl: String? = null,
    @SerialName("fallbackProxyUrl") val fallbackProxyUrl: String? = null,
    val type: String = "hls",
    val quality: String = "Auto",
    val audio: String = "",
    val audioLabel: String = "",
    val audioIndex: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("streamHeaders") val streamHeaders: Map<String, String> = emptyMap(),
) {
    /** The site's relative proxy path, with its leading JSON-safe query intact. */
    fun proxyPath(): String = fallbackProxyUrl?.takeIf { it.isNotBlank() } ?: ""
}

@Serializable
data class AltStreamDiagnostics(
    @SerialName("targetMeta") val targetMeta: AltStreamTargetMeta? = null,
)

@Serializable
data class AltStreamTargetMeta(
    val title: String = "",
    val year: String = "",
)
