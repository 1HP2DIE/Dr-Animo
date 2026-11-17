package com.animo.app.subtitle.match

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One title, reduced to something comparison-safe.
 *
 * [text] is the whole title normalised; [tokens] is what matching works on; [mainTokens] is the
 * part before a subtitle separator ("Frieren: Beyond Journey's End" → "frieren"). Franchises are
 * usually named in that first part, so a hit there is worth more than a hit in the tail.
 */
data class NormalizedTitle(
    val raw: String,
    val text: String,
    val tokens: List<String>,
    val mainTokens: List<String>,
) {
    val isEmpty: Boolean get() = tokens.isEmpty()
}

/**
 * Title normalisation for matching release names against the app's titles.
 *
 * Deliberately more aggressive than a display formatter:
 *  - diacritics are folded (`Pokémon` → `pokemon`, `Sōsō` → `soso`), because sites are inconsistent;
 *  - apostrophes vanish instead of becoming a space (`Journey's` → `journeys`, not `journey s`);
 *  - `&`, `+` become `and`;
 *  - punctuation is a separator, never part of a token;
 *  - a subtitle separator (`:`, ` - `, `~`, `|`) is remembered, so the franchise name can be
 *    weighted separately.
 *
 * Results are cached: the same 20 release names are scored repeatedly while a picker is open.
 */
object TitleNormalizer {

    private const val CACHE_LIMIT = 768

    private val cache = ConcurrentHashMap<String, NormalizedTitle>()

    private val LIGATURES = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l",
        'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
    )

    private val COMBINING = Regex("""\p{Mn}+""")
    private val APOSTROPHES = Regex("""['’‘`´ʼ]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    /** "Frieren: Beyond…", "Frieren - Beyond…", "Frieren ~ Beyond…". Not "Spider-Man". */
    private val SUBTITLE_SEPARATOR = Regex("""\s*[:~|]\s*|\s+[-–—]\s+""")

    fun normalize(raw: String): NormalizedTitle {
        cache[raw]?.let { return it }

        var work = raw
        LIGATURES.forEach { (source, replacement) -> work = work.replace(source.toString(), replacement) }
        work = Normalizer.normalize(work, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "")
            .replace("&", " and ")
            .replace("+", " and ")

        val separator = SUBTITLE_SEPARATOR.find(work)
        val mainRaw = if (separator != null) work.substring(0, separator.range.first) else work

        val normalized = NormalizedTitle(
            raw = raw,
            text = tokensOf(work).joinToString(" "),
            tokens = tokensOf(work),
            mainTokens = tokensOf(mainRaw),
        )

        if (cache.size > CACHE_LIMIT) cache.clear()
        cache[raw] = normalized
        return normalized
    }

    private fun tokensOf(value: String): List<String> =
        NON_WORD.replace(value, " ")
            .let { SPACES.replace(it, " ").trim() }
            .split(' ')
            .filter { it.isNotBlank() }

    /** Tokens that carry no identity: release chatter, container words, article glue. */
    val GENERIC: Set<String> = setOf(
        "the", "and", "a", "an", "of", "in", "on", "to", "no", "wa", "ga", "de",
        "season", "seasons", "part", "series", "movie", "film", "ova", "ona", "oad",
        "special", "specials", "episode", "episodes", "complete", "batch", "collection",
        "uncensored", "version", "extended", "remastered", "tv", "web", "webrip", "webdl",
        "bd", "bdrip", "bluray", "hdrip", "dvdrip", "x264", "x265", "h264", "h265", "hevc",
        "av1", "aac", "ac3", "flac", "opus", "10bit", "8bit", "hdr", "uhd", "4k",
        "480p", "540p", "720p", "1080p", "1440p", "2160p", "multi", "dual", "audio",
        "sub", "subs", "subbed", "dub", "dubbed", "raw", "raws", "repack", "proper",
        "japanese", "english", "hindi", "spanish", "french", "german", "italian",
        "nf", "cr", "amzn", "amazon", "netflix", "hulu", "dsnp", "atvp", "group", "release",
        "eng", "jpn", "hin", "und", "vostfr", "truefrench",
    )
}
