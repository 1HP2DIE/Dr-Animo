package com.animo.app.player

import com.animo.app.domain.model.StreamSubtitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `tracks[]` array of a `getSources` answer: subtitle files plus one seek-preview index.
 *
 * Shape measured live (2026-11-17):
 * ```
 * {"file":".../subtitles/track_0_CR_English_eng.ass","label":"English (CR English)",
 *  "kind":"captions","default":true,"styled":true,"fonts":[...]}
 * {"file":".../subtitles/track_0_CR_English_eng.vtt","label":"English (CR English)","kind":"captions"}
 * {"file":".../preview/thumbnails.vtt","kind":"thumbnails"}
 * ```
 * Every language arrives twice — once as styled ASS, once as WebVTT. The app draws subtitles with
 * the viewer's own style, so the ASS styling buys nothing and its override tags are noise: the VTT
 * copy wins, and the `default` flag is carried over from whichever copy had it.
 *
 * Pure JVM so it can be unit-tested against captured payloads.
 */
object StreamTracks {

    data class Parsed(val subtitles: List<StreamSubtitle>, val previewVtt: String?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(sourcesJson: String): Parsed {
        val root = runCatching { json.parseToJsonElement(sourcesJson) as? JsonObject }.getOrNull()
            ?: return Parsed(emptyList(), null)
        val tracks = root["tracks"] as? JsonArray ?: return Parsed(emptyList(), null)

        var preview: String? = null
        // label -> best copy so far; insertion order keeps the source's own ordering.
        val byLabel = LinkedHashMap<String, StreamSubtitle>()

        for (element in tracks) {
            val track = element as? JsonObject ?: continue
            val file = track.string("file")?.takeIf { it.startsWith("http") } ?: continue
            when (track.string("kind")?.lowercase()) {
                "thumbnails" -> if (preview == null) preview = file
                "captions", "subtitles", null -> {
                    val label = track.string("label")?.trim().orEmpty().ifBlank { languageOf(file) ?: "Subtitles" }
                    val candidate = StreamSubtitle(
                        url = file,
                        label = label,
                        language = languageOf(file),
                        isDefault = track["default"]?.jsonPrimitive?.booleanOrNull == true,
                        forced = isForcedLabel(label),
                    )
                    val existing = byLabel[label]
                    byLabel[label] = when {
                        existing == null -> candidate
                        // Prefer the VTT copy, but never lose a `default` flag set on the other one.
                        isVtt(candidate.url) && !isVtt(existing.url) ->
                            candidate.copy(isDefault = candidate.isDefault || existing.isDefault)
                        else -> existing.copy(isDefault = existing.isDefault || candidate.isDefault)
                    }
                }
            }
        }
        return Parsed(byLabel.values.toList(), preview)
    }

    /**
     * The track to switch on by itself, or `null` to leave subtitles off.
     *
     * - Original audio: the source's default, else the first English track, else nothing.
     * - Dub: only a "Forced" (signs & songs) English track — full subtitles over a dub are noise.
     */
    fun autoPick(subtitles: List<StreamSubtitle>, dub: Boolean): StreamSubtitle? {
        val english = subtitles.filter { it.isEnglish() }
        return if (dub) {
            english.firstOrNull { it.forced }
        } else {
            subtitles.firstOrNull { it.isDefault && !it.forced }
                ?: english.firstOrNull { !it.forced }
        }
    }

    /** "English (Forced)", "English (Signs & Songs)" — the track a dub wants on. */
    fun isForcedLabel(label: String): Boolean =
        label.contains("forced", ignoreCase = true) || label.contains("signs", ignoreCase = true)

    private fun StreamSubtitle.isEnglish(): Boolean =
        language in setOf("en", "eng") || label.startsWith("English", ignoreCase = true)

    private fun isVtt(url: String) = url.substringBefore('?').endsWith(".vtt", ignoreCase = true)

    /** `track_0_CR_English_eng.vtt` → `eng`. */
    private fun languageOf(url: String): String? =
        LANG_SUFFIX.find(url.substringBefore('?').substringAfterLast('/'))?.groupValues?.get(1)?.lowercase()

    private val LANG_SUFFIX = Regex("""_([a-zA-Z]{2,3})\.(?:vtt|ass|ssa|srt)$""")

    private fun JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/**
 * One cell of a seek-preview sprite sheet: `sprite_0.webp#xywh=160,0,160,90` for 00:05–00:10.
 */
data class PreviewCell(
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Parser for the thumbnails WebVTT index. Relative sprite paths resolve against [baseUrl]. */
object PreviewVtt {

    private val TIMING = Regex("""(\S+)\s+-->\s+(\S+)""")
    private val XYWH = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""")

    fun parse(content: String, baseUrl: String): List<PreviewCell> {
        // `…/preview/thumbnails.vtt` is a file inside the folder; `…/thumbnails_vtt/<id>` (no
        // extension) is itself the folder its sprites live under — both measured live.
        val clean = baseUrl.substringBefore('?').trimEnd('/')
        val base = if (clean.substringAfterLast('/').contains('.')) {
            clean.substringBeforeLast('/') + "/"
        } else {
            "$clean/"
        }
        val lines = content.replace("\r\n", "\n").lines()
        val cells = ArrayList<PreviewCell>()
        var i = 0
        while (i < lines.size) {
            val timing = TIMING.find(lines[i])
            if (timing == null) {
                i++
                continue
            }
            val target = lines.getOrNull(i + 1)?.trim().orEmpty()
            val start = com.animo.app.subtitle.SubtitleParser.parseTimecode(timing.groupValues[1])
            val end = com.animo.app.subtitle.SubtitleParser.parseTimecode(timing.groupValues[2])
            val box = XYWH.find(target)
            if (start != null && end != null && box != null) {
                val path = target.substringBefore('#')
                cells += PreviewCell(
                    startMs = start,
                    endMs = end,
                    imageUrl = if (path.startsWith("http")) path else base + path.trimStart('/'),
                    x = box.groupValues[1].toInt(),
                    y = box.groupValues[2].toInt(),
                    width = box.groupValues[3].toInt(),
                    height = box.groupValues[4].toInt(),
                )
            }
            i += 2
        }
        return cells
    }

    /** Binary search: cells are in time order and contiguous. */
    fun at(cells: List<PreviewCell>, positionMs: Long): PreviewCell? {
        if (cells.isEmpty()) return null
        var low = 0
        var high = cells.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cell = cells[mid]
            when {
                positionMs < cell.startMs -> high = mid - 1
                positionMs >= cell.endMs -> low = mid + 1
                else -> return cell
            }
        }
        return cells.getOrNull(low.coerceAtMost(cells.lastIndex))
    }
}
