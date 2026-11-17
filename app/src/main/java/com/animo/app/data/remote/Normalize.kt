package com.animo.app.data.remote

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter

/**
 * Pure normalisation helpers for the measured API messiness (research/DATA_CONTRACT.md §2, §3, §7).
 *
 * Everything here is deliberately free of android.* so it can be unit-tested on the JVM — the ingest
 * path must never be the reason a sync fails.
 */
object Normalize {

    // ---------------------------------------------------------------- HTML entities

    private val ENTITY_REGEX = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{0,9});?")

    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "laquo" to "«",
        "raquo" to "»",
        "middot" to "·",
        "bull" to "•",
        "times" to "×",
        "divide" to "÷",
        "deg" to "°",
        "trade" to "™",
        "copy" to "©",
        "reg" to "®",
        "hearts" to "♥",
        "star" to "★",
        "eacute" to "é",
        "egrave" to "è",
        "ecirc" to "ê",
        "agrave" to "à",
        "acirc" to "â",
        "ccedil" to "ç",
        "ouml" to "ö",
        "uuml" to "ü",
        "auml" to "ä",
        "szlig" to "ß",
        "ntilde" to "ñ",
    )

    /**
     * Decodes the entities the API actually emits (`&#39;`, `&amp;`, `&nbsp` — note the missing
     * semicolon that appears verbatim in `season`). Unknown entities are left untouched so we never
     * silently delete text.
     */
    fun unescapeHtml(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        return ENTITY_REGEX.replace(raw) { match ->
            val token = match.groupValues[1]
            if (token.startsWith("#")) {
                val code = if (token.length > 1 && (token[1] == 'x' || token[1] == 'X')) {
                    token.substring(2).toIntOrNull(16)
                } else {
                    token.substring(1).toIntOrNull()
                }
                if (code != null && code in 1..0x10FFFF) {
                    String(Character.toChars(code))
                } else {
                    match.value
                }
            } else {
                NAMED_ENTITIES[token.lowercase()] ?: match.value
            }
        }
    }

    /** Collapses the `&nbsp;` padding that survives unescaping and trims. */
    fun cleanText(raw: String): String =
        unescapeHtml(raw).replace('\u00A0', ' ').trim()

    // ---------------------------------------------------------------- season

    /**
     * 11 observed spellings -> 4 real values. Anything unrecognised (including the literal
     * `&nbsp`, `""` and `unknown`) becomes `unknown`.
     */
    fun normalizeSeason(raw: String): String =
        when (cleanText(raw).lowercase()) {
            "fall", "autumn" -> "fall"
            "summer" -> "summer"
            "spring" -> "spring"
            "winter" -> "winter"
            else -> "unknown"
        }

    // ---------------------------------------------------------------- status

    fun normalizeStatus(raw: String): String =
        when (cleanText(raw).lowercase().replace(Regex("\\s+"), "_")) {
            "finished_airing", "finished", "completed", "complete" -> "finished_airing"
            "currently_airing", "airing", "ongoing", "current" -> "currently_airing"
            "not_yet_aired", "upcoming", "unreleased", "not_yet" -> "not_yet_aired"
            else -> ""
        }

    // ---------------------------------------------------------------- rating

    /** `PG`, `G`, `R`, `R+`, `PG-13` survive lowercased; `None`, `N/A`, `-` collapse to `""`. */
    fun normalizeRating(raw: String): String =
        when (val value = cleanText(raw).lowercase()) {
            "", "none", "n/a", "na", "-", "unknown", "null" -> ""
            else -> value
        }

    // ---------------------------------------------------------------- time

    private val UTC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** `"2026-11-16 17:40:55"` (UTC) -> epoch seconds. Unparseable / empty -> 0. */
    fun parseUtcSeconds(raw: String): Long {
        val value = raw.trim()
        if (value.isEmpty()) return 0L
        return try {
            LocalDateTime.parse(value, UTC_SECONDS).toEpochSecond(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            0L
        }
    }

    /**
     * `next_air_schedule_time` is a plain epoch-second int that is frequently **in the past**
     * (DATA_CONTRACT §7.8), so it is only meaningful when it is in the future.
     */
    fun sanitizeNextAirEpoch(raw: Long?, nowSeconds: Long = System.currentTimeMillis() / 1000): Long? =
        raw?.takeIf { it > nowSeconds && it < 4_102_444_800L }

    // ---------------------------------------------------------------- numeric / csv

    /** `episodes` is a String on the wire; `""` and absent both mean "unknown", never 0. */
    fun parseIntOrNull(raw: String?): Int? = raw?.trim()?.toIntOrNull()

    fun parseDoubleOrNull(raw: String?): Double? = raw?.trim()?.toDoubleOrNull()

    fun joinCsv(values: List<String>): String =
        values.map { cleanText(it) }.filter { it.isNotEmpty() }.joinToString(SEPARATOR)

    /**
     * Splits on a bare comma rather than on `", "`: the API joins with `", "` but titles seen live
     * also carry `"Comedy,SCI-FI"` (no space), and splitting on `", "` would fuse that into one genre
     * — which then shows up in the filter sheet as a value nobody can match.
     */
    fun splitCsv(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private const val SEPARATOR = ", "

    // ---------------------------------------------------------------- browse keys

    /**
     * Key for the A–Z index and the title sorts (`anime.titleNorm`): leading punctuation is stripped
     * so `「Komi-san」` files under **k**, not under a bracket — the brackets are decoration in every
     * observed title, the word after them is what a user scans for. Articles are deliberately kept
     * ("The …" sorts under T) because this is a browse index, not a library catalog.
     *
     * Lowercased with `Locale.ROOT` semantics (Kotlin's no-arg `lowercase()`), so a Turkish device
     * locale cannot turn "I" into "ı" and break the LIKE prefix match.
     */
    fun titleSortKey(raw: String): String =
        cleanText(raw)
            .replace(LEADING_PUNCTUATION, "")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .lowercase()

    /** Folds a browse letter to the form stored in `titleNorm`; `null` when it is not a letter/digit. */
    fun normalizeLetter(raw: Char?): Char? =
        raw?.lowercaseChar()?.takeIf { it.isLetterOrDigit() }

    private val LEADING_PUNCTUATION = Regex("^[^\\p{L}\\p{N}]+")
    private val WHITESPACE_RUN = Regex("\\s+")

    /** `slug` is unique in the API but defensive: a blank slug would break the unique index. */
    fun slugOrFallback(slug: String, id: Int): String =
        slug.trim().ifEmpty { "anime-$id" }

    // ---------------------------------------------------------------- type

    /**
     * The `type` column is a free-text mess: live values include `TV`, `TV_SHORT`, `Movie`, `ONA`,
     * `OVA`, `Special`, `TV Special`, `Music`, `PV`, `CM` — plus a one-off `conspiracy`. Only the
     * known shapes become a filter chip; everything else is dropped so the sheet never offers a
     * value that means nothing to a user.
     *
     * Matching is done on `REPLACE(type,'_',' ')` lowercased (see `FilterSql`), so a canonical label
     * written back out as a lowercase, space-separated string matches every stored spelling.
     */
    fun normalizeType(raw: String): String? =
        when (cleanText(raw).lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")) {
            "tv" -> "TV"
            "tv short" -> "TV Short"
            "tv special" -> "TV Special"
            "movie" -> "Movie"
            "ova" -> "OVA"
            "ona" -> "ONA"
            "special" -> "Special"
            "music" -> "Music"
            "pv" -> "PV"
            "cm" -> "CM"
            else -> null
        }

    /** Inverse of [normalizeType] for the SQL side: the value that `LOWER(REPLACE(type,'_',' '))` yields. */
    fun typeMatchKey(canonical: String): String? =
        normalizeType(canonical)?.lowercase()

    /** Short diagnostic form used in log lines only — never logged with a payload attached. */
    fun truncate(raw: String, max: Int = 160): String =
        if (raw.length <= max) raw else raw.substring(0, max) + "…"
}
