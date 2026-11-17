package com.animo.app.subtitle

/**
 * Parser for the three subtitle formats people actually load onto a phone: **SubRip** (`.srt`),
 * **WebVTT** (`.vtt`) and **ASS/SSA** (`.ass`, `.ssa`).
 *
 * Assumptions, deliberately:
 *  - subtitles are text, not styled markup — inline styling (`<i>`, ASS override blocks) is dropped,
 *    because the app's own style settings are the single source of truth for how a line looks;
 *  - timing is all we need; there is no karaoke/positioning support;
 *  - the file is small and already decoded to a String (the caller handles bytes/charset).
 *
 * Anything unreadable is skipped instead of failing the whole file: a couple of broken cues should
 * not cost the viewer the other 400.
 */
object SubtitleParser {

    /** `hh:mm:ss,mmm`, `mm:ss.mmm`, `h:mm:ss.cc` — hours optional, separator `,` or `.`. */
    private val TIMECODE = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    private val BR_TAG = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[^>]*>")
    private val ASS_OVERRIDE = Regex("""\{[^}]*}""")
    private val ALIGN_SETTING = Regex("""align\s*:\s*(start|left|center|middle|end|right)""", RegexOption.IGNORE_CASE)
    private val ASS_ALIGN = Regex("""\\an([1-9])""")

    private val DEFAULT_ASS_FIELDS = listOf(
        "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
    )

    /**
     * Parses [content], using [fileName] only as a hint when the body itself is ambiguous (an
     * extension-less file downloaded from a link, for example).
     */
    fun parse(fileName: String, content: String): List<SubtitleCue> {
        val normalized = content
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()

        val lower = fileName.lowercase()
        val cues = when {
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> parseAss(normalized)
            lower.endsWith(".vtt") -> parseSrtStyle(normalized)
            lower.endsWith(".srt") -> parseSrtStyle(normalized)
            normalized.startsWith("WEBVTT", ignoreCase = true) -> parseSrtStyle(normalized)
            normalized.contains("[Events]") -> parseAss(normalized)
            normalized.contains("-->") -> parseSrtStyle(normalized)
            else -> emptyList()
        }

        return cues
            .filter { it.text.isNotBlank() && it.endMs > it.startMs }
            .sortedBy { it.startMs }
    }

    // ---------------------------------------------------------------- SubRip + WebVTT

    /**
     * SRT and WebVTT share a cue grammar closely enough to use one reader: a block is
     * `[id line] / timecode / text lines`, separated by a blank line. VTT extras (the `WEBVTT`
     * header, `NOTE`/`STYLE`/`REGION` blocks, cue settings such as `align:start`) are handled here.
     */
    private fun parseSrtStyle(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("""\n[ \t]*\n"""))
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
            if (lines.isEmpty()) continue

            val first = lines.first().trim()
            if (first.startsWith("WEBVTT", ignoreCase = true)) {
                // The header may share the block with the first cue, so keep reading its lines.
                val rest = lines.drop(1).dropWhile { it.isBlank() }
                if (rest.isEmpty()) continue
                readSrtCue(rest)?.let(cues::add)
                continue
            }
            if (first.startsWith("NOTE", ignoreCase = true) ||
                first.startsWith("STYLE", ignoreCase = true) ||
                first.startsWith("REGION", ignoreCase = true)
            ) {
                continue
            }
            readSrtCue(lines)?.let(cues::add)
        }
        return cues
    }

    private fun readSrtCue(lines: List<String>): SubtitleCue? {
        val timeIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeIndex < 0) return null

        val header = lines[timeIndex]
        val arrow = header.indexOf("-->")
        val start = parseTimecode(header.substring(0, arrow)) ?: return null
        val tail = header.substring(arrow + 3).trim()
        val endToken = tail.substringBefore(' ').trim()
        val end = parseTimecode(endToken) ?: return null

        val textLines = lines.drop(timeIndex + 1)
        val text = cleanText(textLines.joinToString("\n"))
        if (text.isBlank()) return null

        val alignment = ALIGN_SETTING.find(tail)?.groupValues?.get(1)?.toAlignment()
            ?: CueAlignment.CENTER
        return SubtitleCue(startMs = start, endMs = end, text = text, alignment = alignment)
    }

    private fun String.toAlignment(): CueAlignment = when (lowercase()) {
        "start", "left" -> CueAlignment.START
        "end", "right" -> CueAlignment.END
        else -> CueAlignment.CENTER
    }

    // ---------------------------------------------------------------- ASS / SSA

    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var fields: List<String> = DEFAULT_ASS_FIELDS

        for (raw in content.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("[") -> inEvents = line.equals("[Events]", ignoreCase = true)

                inEvents && line.startsWith("Format:", ignoreCase = true) ->
                    fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }

                inEvents && line.startsWith("Comment:", ignoreCase = true) -> Unit

                inEvents && line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val body = line.substringAfter(':').trim()
                    val parts = body.split(',', limit = fields.size.coerceAtLeast(1))
                    val startIndex = fields.indexOf("start")
                    val endIndex = fields.indexOf("end")
                    val textIndex = fields.indexOf("text")
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
                    if (parts.size <= maxOf(startIndex, endIndex, textIndex)) continue

                    val start = parseTimecode(parts[startIndex]) ?: continue
                    val end = parseTimecode(parts[endIndex]) ?: continue
                    val rawText = parts[textIndex]
                    val text = cleanAssText(rawText)
                    if (text.isNotBlank() && end > start) {
                        cues += SubtitleCue(
                            startMs = start,
                            endMs = end,
                            text = text,
                            alignment = assAlignment(rawText),
                        )
                    }
                }
            }
        }
        return cues
    }

    /** `\an1..\an9` is the only positioning hint that maps onto a simple horizontal alignment. */
    private fun assAlignment(raw: String): CueAlignment =
        when (ASS_ALIGN.find(raw)?.groupValues?.get(1)?.toIntOrNull()) {
            1, 4, 7 -> CueAlignment.START
            3, 6, 9 -> CueAlignment.END
            else -> CueAlignment.CENTER
        }

    private fun cleanAssText(raw: String): String = raw
        .replace(ASS_OVERRIDE, "")
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ")
        .let(::cleanText)

    // ---------------------------------------------------------------- shared

    /** Milliseconds for the first timecode in [raw]; `null` when there is none. */
    fun parseTimecode(raw: String): Long? {
        val match = TIMECODE.find(raw) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        val fraction = match.groupValues[4]

        // 1 digit = tenths, 2 = hundredths (ASS), 3 = milliseconds (SRT/VTT).
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return ((hours * 3_600L) + (minutes * 60L) + seconds) * 1_000L + millis
    }

    private fun cleanText(raw: String): String = raw
        .replace(BR_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace(ASS_OVERRIDE, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()
}

/** Convenience for the settings pane: does this filename look like a subtitle file? */
fun isSubtitleFileName(name: String): Boolean {
    val lower = name.lowercase()
    return SUBTITLE_EXTENSIONS.any { lower.endsWith(it) }
}

private val SUBTITLE_EXTENSIONS = listOf(".srt", ".vtt", ".ass", ".ssa", ".sub")
