package com.animo.app.player.movielink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `window.__PD2` config the `/p/<token>` shell carries, and the choice of which URL to play.
 *
 * Measured shape (one line, followed by unrelated JS on the same line):
 * ```
 * window.__PD2 = {"master":"https://watch.movielink.tv/m/<token>",
 *                 "mp4":"https://fsl.movielink.tv/p/<token>",
 *                 "episodes":[{"label":"Movie","master":"…","mp4":"…"}], …}
 * ```
 * A series puts **every** episode into this one payload, so the page itself is per-title, not
 * per-episode. [choose] therefore needs an episode number, and the resolver reads it from the embed
 * URL (`?ep=2`, 1-based) — without one it plays the first entry, which is the page's own default.
 *
 * Deliberately kept free of IO and `android.*` so all of it is unit-testable offline.
 */
internal object MovieLinkPd2 {

    /** One playable entry: the top-level config or one `episodes[]` element. */
    data class Source(
        val label: String?,
        val masterUrl: String?,
        val mp4Url: String?,
        /** The provider's own episode number, e.g. `1221`. Null on older payloads. */
        val number: Int? = null,
        /** Last number of a batch entry (`num_end`), equal to [number] for a single episode. */
        val numEnd: Int? = null,
    )

    data class Payload(val topLevel: Source, val episodes: List<Source>)

    /** The URL to hand to the player plus the container it implies. */
    data class Choice(val url: String, val hls: Boolean)

    private const val MARKER = "__PD2"

    /**
     * How far after the marker the object literal may start. The shell is ~13 KB; the cap only
     * exists so a page that mentions `__PD2` without ever opening an object cannot make this scan
     * the whole document on every resolve.
     */
    private const val MARKER_WINDOW = 256

    private val ASSIGNMENT = Regex("""__PD2\s*=\s*""")

    /**
     * `isLenient` is required: the site's object is hand-written JS and has been seen without
     * quotes around keys. `ignoreUnknownKeys` keeps the many uninteresting fields (`hash`, …) from
     * ever mattering.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Pulls the config out of the shell HTML.
     *
     * @return `null` when the marker is absent or the object literal cannot be parsed. The resolver
     * turns both into [com.animo.app.player.ResolveException.Parse]; nothing here throws, so a
     * malformed page never looks like a network problem.
     */
    fun extract(html: String): Payload? {
        val literal = objectLiteral(html) ?: return null
        val root = runCatching { json.parseToJsonElement(literal) as? JsonObject }.getOrNull() ?: return null
        val episodes = (root["episodes"] as? JsonArray).orEmpty()
            .mapNotNull { element -> (element as? JsonObject)?.let { sourceOf(it) } }
        return Payload(topLevel = sourceOf(root), episodes = episodes)
    }

    /**
     * Picks the entry to play.
     *
     * The **top-level** entry is authoritative: it is the page's own default stream, which for a
     * per-episode `/p/<token>` is that episode. `episodes[]` is only consulted when the caller asks
     * for a specific number, because a series payload lists every episode under one token and the
     * list order is not guaranteed to be the order the provider's episode numbering uses.
     *
     * The progressive MP4 is preferred over the HLS master within the same entry: the MP4 needs no
     * headers at all, supports `Range`, and — unlike the playlist, which is served as `image/jpeg`
     * on purpose — has no content-type trap. HLS is only used when the entry has no MP4.
     *
     * @param episodeNumber 1-based, from the embed URL; clamped, so a stale deep link still plays.
     */
    fun choose(payload: Payload, episodeNumber: Int?): Choice? {
        val source = when {
            episodeNumber == null || payload.episodes.isEmpty() -> payload.topLevel
            else -> payload.episodes[(episodeNumber - 1).coerceIn(0, payload.episodes.lastIndex)]
        }
        // A per-episode entry can be sparse (labels only); the page default is still a valid stream.
        val candidates = if (source === payload.topLevel) listOf(source) else listOf(source, payload.topLevel)
        for (candidate in candidates) {
            candidate.mp4Url?.takeIf(::isHttpUrl)?.let { return Choice(it, hls = false) }
            candidate.masterUrl?.takeIf(::isHttpUrl)?.let { return Choice(it, hls = true) }
        }
        return null
    }

    private fun sourceOf(element: JsonObject): Source = Source(
        label = (element["label"] as? JsonPrimitive)?.contentOrNull,
        masterUrl = (element["master"] as? JsonPrimitive)?.contentOrNull,
        mp4Url = (element["mp4"] as? JsonPrimitive)?.contentOrNull,
        number = intOf(element["number"]),
        numEnd = intOf(element["num_end"]),
    )

    /** The payload writes numbers as JSON numbers; a string is tolerated rather than dropped. */
    private fun intOf(element: kotlinx.serialization.json.JsonElement?): Int? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.toIntOrNull()
        else -> null
    }

    /**
     * Returns the `{…}` that follows `__PD2 =`, using a string-aware brace match.
     *
     * A plain regex cannot do this: the object holds only strings, but the JS that follows it on the
     * same line contains `}`, and a non-aware scan would cut the literal in the middle.
     */
    private fun objectLiteral(html: String): String? {
        var openAt = -1
        val assignment = ASSIGNMENT.find(html)
        if (assignment != null) {
            openAt = html.indexOf('{', assignment.range.last + 1)
                .takeIf { it >= 0 && it - assignment.range.last <= MARKER_WINDOW }
                ?: -1
        }
        if (openAt < 0) {
            // Fallback: the marker exists but not as a plain assignment (e.g. inside a wrapper).
            val markerAt = html.indexOf(MARKER)
            if (markerAt < 0) return null
            openAt = html.indexOf('{', markerAt + MARKER.length)
                .takeIf { it >= 0 && it - markerAt <= MARKER_WINDOW }
                ?: return null
        }
        return balancedObject(html, openAt)
    }

    /** Text from [openAt] to its matching `}`, ignoring braces inside double-quoted strings. */
    private fun balancedObject(source: String, openAt: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in openAt until source.length) {
            val ch = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openAt, index + 1)
                }
            }
        }
        return null
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")
}
