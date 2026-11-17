package com.animo.app.player

import com.animo.app.domain.model.StreamSubtitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wire format of the file-mirror source (Server 3), measured live on 2026-11-18. Pure JVM.
 *
 * Chain:
 * 1. `GET {api}/api/flix/{anilistId}/{episode}` → `servers[]` of `{dataType: sub|dub, dataLink,
 *    hardsub}`. Sub and dub usually point at the **same** file: it is one MKV carrying both audio
 *    tracks, so "dub" means "pick the English audio inside it".
 * 2. `GET {player}/d/{aid}/__data.json` (SvelteKit, devalue-encoded) → `fileId`, `filename`,
 *    `download.base`, `download.token`.
 * 3. `GET {base}/download/{fileId}/progress?token=` until `"ready"`, then
 *    `GET {base}/download/{fileId}?token=&file=` → 302 to a signed, range-capable MKV
 *    (H.264 + AAC jpn/eng + ASS; measured 206 on a mid-file range).
 * 4. `GET {player}/e/{aid}/__data.json?v=` → chapters (opening/ending), side-car subtitles and a
 *    seek-preview index. The streaming URL in that payload is encrypted, which is why playback goes
 *    through the download route instead.
 */
object MirrorProtocol {

    data class Server(val dataType: String, val link: String, val hardsub: Boolean)

    data class FileTicket(val fileId: String, val filename: String, val base: String, val token: String)

    data class Extras(
        val intro: LongRange?,
        val outro: LongRange?,
        val subtitles: List<StreamSubtitle>,
        val previewVtt: String?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun servers(body: String): List<Server> {
        val root = parseObject(body) ?: return emptyList()
        val list = root["servers"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            val link = server.str("dataLink")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Server(
                dataType = server.str("dataType")?.lowercase().orEmpty(),
                link = link,
                hardsub = server.bool("hardsub") == true,
            )
        }
    }

    /**
     * The server entry to play. A soft-subbed file is preferred for both languages: its subtitles
     * are ours to style and switch off, a hard-subbed one is not.
     */
    fun pick(servers: List<Server>, dub: Boolean): Server? {
        val wanted = if (dub) "dub" else "sub"
        val matching = servers.filter { it.dataType == wanted }
        return matching.firstOrNull { !it.hardsub } ?: matching.firstOrNull()
    }

    /** `https://player/e/rjyccuaq8h5n?v=1` → (`rjyccuaq8h5n`, `1`). */
    fun embedId(link: String): Pair<String, String>? {
        val path = link.substringBefore('?').trimEnd('/')
        val id = path.substringAfterLast("/e/", missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
        val variant = Regex("""[?&]v=([^&]+)""").find(link)?.groupValues?.get(1) ?: "1"
        return id to variant
    }

    fun ticket(dataJson: String): FileTicket? {
        val root = Devalue.root(dataJson) as? JsonObject ?: return null
        val video = root["video"] as? JsonObject ?: return null
        val download = root["download"] as? JsonObject ?: return null
        return FileTicket(
            fileId = video.str("fileId") ?: return null,
            filename = video.str("filename") ?: return null,
            base = download.str("base")?.trimEnd('/') ?: return null,
            token = download.str("token") ?: return null,
        )
    }

    /** The progress endpoint answers server-sent events: `data: {"percent":100,"status":"ready"}`. */
    fun isReady(progressBody: String): Boolean =
        progressBody.contains("\"status\":\"ready\"") || progressBody.contains("\"status\": \"ready\"")

    fun extras(dataJson: String): Extras {
        val root = Devalue.root(dataJson) as? JsonObject ?: return Extras(null, null, emptyList(), null)
        val chapters = (root["chapters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        fun chapterNamed(vararg names: String): JsonObject? = chapters.firstOrNull { chapter ->
            val title = chapter.str("title")?.lowercase().orEmpty()
            names.any { title == it || title.startsWith("$it ") }
        }

        val intro = range(root["intro_chapter"] as? JsonObject ?: chapterNamed("opening", "op", "intro"))
        val outro = range(root["outro_chapter"] as? JsonObject ?: chapterNamed("ending", "ed", "outro"))

        val subtitles = (root["subtitles"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val url = track.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val label = track.str("language")?.trim().orEmpty().ifBlank { "Subtitles" }
            StreamSubtitle(
                url = url,
                label = label,
                language = if (label.startsWith("English", ignoreCase = true)) "eng" else null,
                isDefault = track.bool("default") == true,
                forced = StreamTracks.isForcedLabel(label),
            )
        }
        return Extras(
            intro = intro,
            outro = outro,
            subtitles = subtitles,
            previewVtt = root.str("thumbnails_vtt")?.takeIf { it.startsWith("http") },
        )
    }

    /** Chapter `{start, end}` in seconds → milliseconds; zero-length means "no marker". */
    private fun range(chapter: JsonObject?): LongRange? {
        chapter ?: return null
        val start = chapter.num("start") ?: return null
        val end = chapter.num("end") ?: return null
        if (end <= start) return null
        return (start * 1000).toLong() until (end * 1000).toLong()
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}

/**
 * Decoder for SvelteKit's `__data.json` ("devalue"): every value lives in one flat `data` array and
 * objects/arrays hold *indices* into it. Only the plain-JSON subset is handled — enough for the
 * payloads above; tagged types (dates, maps) come back as their raw array.
 */
object Devalue {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The resolved root of the first data node, or `null` when there is none. */
    fun root(body: String): JsonElement? {
        val top = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val nodes = top["nodes"] as? JsonArray ?: return null
        for (node in nodes) {
            val obj = node as? JsonObject ?: continue
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "data") continue
            val data = obj["data"] as? JsonArray ?: continue
            if (data.isEmpty()) continue
            return resolve(data, 0, 0)
        }
        return null
    }

    private fun resolve(data: JsonArray, index: Int, depth: Int): JsonElement {
        if (index < 0 || index >= data.size || depth > MAX_DEPTH) return JsonNull
        return when (val value = data[index]) {
            is JsonObject -> JsonObject(
                value.mapValues { (_, ref) -> ref.jsonPrimitive.intOrNull?.let { resolve(data, it, depth + 1) } ?: JsonNull },
            )
            is JsonArray -> {
                // A tagged type starts with its tag name; leave it as-is rather than misread it.
                if (value.firstOrNull()?.let { it is JsonPrimitive && it.isString } == true) {
                    value
                } else {
                    JsonArray(value.map { ref -> (ref as? JsonPrimitive)?.longOrNull?.toInt()?.let { resolve(data, it, depth + 1) } ?: JsonNull })
                }
            }
            else -> value
        }
    }

    private const val MAX_DEPTH = 32
}
