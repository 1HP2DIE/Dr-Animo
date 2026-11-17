package com.animo.app.subtitle.online

import com.animo.app.subtitle.SubtitleFile
import com.animo.app.subtitle.match.MatchCandidate
import com.animo.app.subtitle.match.MatchTarget
import com.animo.app.subtitle.match.TitleMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Locale

/** A language a source can return, normalised to ISO-639-1 for the app. */
data class SubtitleLanguage(val code: String, val label: String)

/**
 * The thing being watched, in the shape every source needs.
 *
 * [titles] is a preference list, not a set: the first entry is the title as the app shows it, the
 * rest are alternates to try when a site indexes the English name instead of the romaji. Episode is
 * `null` for a movie (or an unknown number), which changes the query some sources accept.
 */
data class SubtitleQuery(
    val titles: List<String>,
    val episode: Int? = null,
    val year: Int? = null,
    val malId: String? = null,
    val aniListId: String? = null,
    val isMovie: Boolean = false,
) {
    val primaryTitle: String get() = titles.firstOrNull().orEmpty()

    /** Free-text form most sites' search boxes expect: `Frieren 5`. */
    fun freeText(): String = buildList {
        add(primaryTitle)
        if (!isMovie) episode?.let { add(it.toString()) }
    }.joinToString(" ").trim()
}

/** One downloadable subtitle a source found. */
data class SubtitleResult(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    /** Release or file name as the source lists it — enough for a human to judge a match. */
    val title: String,
    val language: SubtitleLanguage,
    /** `srt`, `ass`, `vtt`, `zip` — lowercase, `null` when the source does not say. */
    val format: String? = null,
    val downloadRef: String,
    val detail: String? = null,
    /** Episode this file belongs to, used to pick the right entry out of a ZIP. */
    val episode: Int? = null,
)

/** The merged answer of every source that replied. [failedSources] is a count, never content. */
data class OnlineSubtitleReport(
    val results: List<SubtitleResult>,
    val failedSources: Int = 0,
)

/**
 * A site subtitles can be fetched from.
 *
 * Implementations must be stateless and safe to call concurrently: [OnlineSubtitleRepository]
 * searches every source in parallel. [search] may throw — the repository turns a failure into a
 * lower `failedSources` count instead of failing the whole search.
 */
interface OnlineSubtitleSource {
    val id: String
    val name: String

    /** ISO-639-1 codes this source can return, for the UI's filter chips. */
    val languages: Set<String> get() = emptySet()

    suspend fun search(query: SubtitleQuery, client: OkHttpClient): List<SubtitleResult>

    suspend fun download(result: SubtitleResult, client: OkHttpClient): SubtitleFile
}

/**
 * Fans a query out to every source, ranks what comes back and downloads the one the viewer picked.
 *
 * Search is best-effort by design: one site being down or changing its markup must cost that site's
 * results, not the whole feature.
 */
class OnlineSubtitleRepository(
    client: OkHttpClient,
    private val sources: List<OnlineSubtitleSource> = DefaultSubtitleSources.all(),
) {

    /**
     * The shared client times out a call at 45 s, which is fine for the catalogue and too tight for
     * a subtitle index: SubtitleCat's origin was measured at ~12 s per page with occasional slow
     * responses. Same connection pool, longer patience.
     */
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(75, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun search(
        query: SubtitleQuery,
        preferredLanguages: List<String> = preferredLanguages(),
    ): OnlineSubtitleReport = coroutineScope {
        if (query.primaryTitle.isBlank()) return@coroutineScope OnlineSubtitleReport(emptyList())

        val outcomes = sources.map { source ->
            async {
                try {
                    // A source that hangs must cost its own results, not the viewer's minute.
                    val value = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source.search(query, client) }
                    value to (value == null)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null to true
                }
            }
        }.awaitAll()

        val results = outcomes.mapNotNull { it.first }.flatten()
        OnlineSubtitleReport(
            results = rank(results, query, preferredLanguages),
            failedSources = outcomes.count { it.second },
        )
    }

    suspend fun download(result: SubtitleResult): SubtitleFile {
        val source = sources.firstOrNull { it.id == result.sourceId }
            ?: throw IllegalStateException("unknown subtitle source ${result.sourceId}")
        return source.download(result, client)
    }

    /**
     * Preferred language first (device language, then English), then an episode-number match, then
     * the formats this app renders best. The stable tie-breaker is title length: a shorter release
     * name is usually the cleaner one.
     */
    private fun rank(
        results: List<SubtitleResult>,
        query: SubtitleQuery,
        preferredLanguages: List<String>,
    ): List<SubtitleResult> {
        val languageOrder = preferredLanguages.withIndex().associate { it.value to it.index }
        val target = MatchTarget(titles = query.titles, year = query.year)
        val evaluated = results
            .distinctBy { "${it.language.code}|${it.title.lowercase(Locale.ROOT)}" }
            .map { it to TitleMatcher.match(target, MatchCandidate(it.title)) }

        // The sources already filtered by episode; this is the last honest filter on "is it the
        // right show". If nothing survives, keep the strongest few rather than an empty picker.
        val accepted = evaluated.filter { it.second.accepted }
        val pool = accepted.ifEmpty { evaluated.sortedByDescending { it.second.score }.take(8) }

        return pool
            .sortedWith(
                compareBy(
                    { languageOrder[it.first.language.code] ?: 50 },
                    { -it.second.score },
                    { if (query.episode != null && mentionsEpisode(it.first.title, query.episode)) 0 else 1 },
                    { if (it.first.format == "srt" || it.first.format == "vtt") 0 else 1 },
                    { it.first.title.length },
                ),
            )
            .map { it.first }
            .take(MAX_RESULTS)
    }

    private fun mentionsEpisode(title: String, episode: Int): Boolean {
        val lower = title.lowercase(Locale.ROOT)
        val padded = " ${episode.toString().padStart(2, '0')} "
        return lower.contains("e${episode.toString().padStart(2, '0')}") ||
            lower.contains("episode ${episode}") ||
            lower.contains("ep${episode.toString().padStart(2, '0')}") ||
            lower.contains(padded)
    }

    private companion object {
        const val MAX_RESULTS = 30

        /** Longer than the slowest measured index (SubtitleCat ~12 s per page). */
        const val SOURCE_TIMEOUT_MS = 40_000L

        fun preferredLanguages(): List<String> {
            val device = Locale.getDefault().language
            return buildList {
                if (device.isNotBlank()) add(device)
                add("en")
                add("hi")
                add("ja")
            }
        }
    }
}

/** Every source the app ships. Order is priority for equal matches. */
object DefaultSubtitleSources {
    fun all(): List<OnlineSubtitleSource> = listOf(
        SubtitleCatSource,
        SubdlSource,
        KitsunekkoSource,
    )
}

/** Normalises the many spellings a site can use into the app's ISO-639-1 codes. */
object SubtitleLanguages {

    /** Display names, filled while the alias table is built. Declared first: the table writes it. */
    private val labels = mutableMapOf<String, String>()

    private val byAlias: Map<String, String> = buildMap {
        fun add(code: String, label: String, vararg aliases: String) {
            put(code, code)
            aliases.forEach { put(it, code) }
            labels[code] = label
        }
        add("en", "English", "eng", "english")
        add("hi", "Hindi", "hin", "hindi")
        add("ja", "Japanese", "jpn", "japanese")
        add("ar", "Arabic", "ara", "arabic")
        add("es", "Spanish", "spa", "sp", "spanish", "español", "espanol", "es-419")
        add("fr", "French", "fre", "fra", "fr", "french", "français", "francais")
        add("pt", "Portuguese", "por", "pob", "pb", "portuguese", "português", "portugues", "portuguese (brazil)")
        add("pt-BR", "Portuguese (Brazil)", "pob", "pb", "brazilian", "brazillianportuguese", "brazilian portuguese", "pt-br")
        add("id", "Indonesian", "ind", "indonesian", "bahasa indonesia")
        add("ko", "Korean", "kor", "korean")
        add("zh", "Chinese", "chi", "zho", "chs", "cht", "chinese", "mandarin", "中文", "zh-cn", "zh-tw", "big5code", "chinesebgcode")
        add("ru", "Russian", "rus", "russian")
        add("tr", "Turkish", "tur", "turkish")
        add("de", "German", "ger", "deu", "german", "deutsch")
        add("it", "Italian", "ita", "italian", "italiano")
        add("ta", "Tamil", "tam", "tamil")
        add("te", "Telugu", "tel", "telugu")
        add("bn", "Bengali", "ben", "bengali", "bangla")
        add("as", "Assamese", "asm", "assamese")
        add("fi", "Finnish", "fin", "finnish")
        add("cs", "Czech", "cze", "ces", "czech")
        add("ms", "Malay", "may", "msa", "malay")
        add("th", "Thai", "tha", "thai")
        add("vi", "Vietnamese", "vie", "vietnamese")
        add("uk", "Ukrainian", "ukr", "ukrainian")
        add("pl", "Polish", "pol", "polish")
        add("nl", "Dutch", "dut", "nld", "dutch")
        add("sv", "Swedish", "swe", "swedish")
        add("he", "Hebrew", "heb", "hebrew", "iw")
        add("fa", "Persian", "per", "fas", "persian", "farsi", "farsipersian")
        add("ur", "Urdu", "urd", "urdu")
        add("tl", "Filipino", "fil", "tgl", "filipino", "tagalog")
    }

    /** Best-effort code from anything a site printed; unknown values keep their raw token. */
    fun codeOf(raw: String?): String {
        val token = raw?.trim()?.lowercase(Locale.ROOT) ?: return "und"
        if (token.isBlank()) return "und"
        return byAlias[token] ?: token.take(12)
    }

    fun of(raw: String?): SubtitleLanguage {
        val code = codeOf(raw)
        return SubtitleLanguage(code = code, label = labelOf(code))
    }

    fun labelOf(code: String): String = labels[code] ?: code.uppercase(Locale.ROOT)

    /** Language chips for the filter row, in the order they appear in the results. */
    fun presentIn(results: List<SubtitleResult>): List<SubtitleLanguage> =
        results.map { it.language }
            .distinctBy { it.code }
            .sortedBy { it.label }
}
