package com.animo.app.subtitle.online

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import com.animo.app.subtitle.SubtitleFile
import com.animo.app.subtitle.SubtitleFiles
import com.animo.app.subtitle.match.MatchCandidate
import com.animo.app.subtitle.match.MatchTarget
import com.animo.app.subtitle.match.TitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * SubDL — a plain-HTML index of release subtitles in ~23 languages, keyless.
 *
 * The flow is three pages deep: search → show page → one season page. The season page is large
 * (~1.4 MB) and holds every language and every episode, each row stamped with an episode range; it
 * is cached for a few minutes so re-opening the picker does not re-download it.
 *
 * Downloads are ZIPs (`dl.subdl.com`), which is why the payload decoder takes the episode number —
 * one row often packs E01–E04 and the right file has to be picked out of the archive.
 */
object SubdlSource : OnlineSubtitleSource {

    override val id: String = "s2"
    override val name: String = "Source B"

    private val BASE: String get() = ProviderVault.url(ProviderKey.SUBTITLE_HOST_B)
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val SHOW_LINK = Regex("""href="(/subtitle/sd\d+/[^"]+)"""")
    private val LANG_BLOCK = Regex("""data-language="([a-z0-9]+)"\s+data-language-name="([^"]+)"""")
    private val ROW = Regex(
        """data-row\s+data-id="(\d+)"[^>]*?data-episode-from="(\d+)"[^>]*?data-episode-to="(\d+)"(.*?)</li>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    /** The CDN host is a vault value; only the path shape is knowledge that lives in code. */
    private val ZIP_LINK: Regex by lazy {
        Regex(
            "href=\"(" + Regex.escape(ProviderVault.url(ProviderKey.SUBTITLE_HOST_B_CDN)) +
                "/subtitle/[^\"]+?\\.zip)\"",
        )
    }
    private val TITLE = Regex("""<h4>([^<]+)</h4>""")

    private val pageCache = ConcurrentHashMap<String, CachedPage>()

    private class CachedPage(val atMs: Long, val html: String)

    override suspend fun search(query: SubtitleQuery, client: OkHttpClient): List<SubtitleResult> {
        val showPath = findShow(client, query) ?: return emptyList()

        val showUrl = absolute(BASE, showPath)
        val showHtml = SubtitleHttp.text(client, showUrl)
        val seasonPath = seasonPathFor(showHtml, showPath)
        val pageUrl = if (seasonPath != null) absolute(BASE, seasonPath) else showUrl
        val page = seasonHtml(client, pageUrl)

        return withContext(Dispatchers.Default) { parse(page, query) }
    }

    override suspend fun download(result: SubtitleResult, client: OkHttpClient): SubtitleFile {
        val bytes = SubtitleHttp.bytes(client, result.downloadRef, referer = BASE)
        return SubtitleFiles.decodePayload(
            bytes = bytes,
            fallbackName = result.downloadRef.substringAfterLast('/'),
            preferEpisode = result.episode,
        ) ?: error("subtitle payload unreadable")
    }

    /**
     * The season page lives under the show path; anything else on the page that says "season" is a
     * related show, and following it silently swaps in a different (usually poorer) entry.
     */
    private fun seasonPathFor(showHtml: String, showPath: String): String? {
        val links = Regex("""href="(""" + Regex.escape(showPath) + """/[^"]*)""")
            .findAll(showHtml)
            .map { it.groupValues[1] }
            .filter { !it.contains('?') && !it.contains('#') }
            .toList()
        // "specials-season" is listed first and is nearly empty — it must never win. The app has no
        // season number of its own, so the first season of this show entry is the honest default.
        return links.firstOrNull { it.endsWith("/first-season") }
            ?: links.firstOrNull { it.contains("season", true) && !it.contains("special", true) }
            ?: links.firstOrNull { it.contains("/movie", true) }
            ?: links.firstOrNull()
    }

    /**
     * SubDL search is a slug path, not a query string: punctuation makes it 404 (`Frieren%3A …`),
     * while the bare title and a couple of progressively shorter slugs all resolve. Candidates are
     * tried in order and the first page with a show link wins.
     */
    private suspend fun findShow(client: OkHttpClient, query: SubtitleQuery): String? {
        for (slug in slugCandidates(query)) {
            val html = runCatching { SubtitleHttp.text(client, BASE + "search/" + slug) }
                .getOrNull() ?: continue
            SHOW_LINK.find(html)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun slugCandidates(query: SubtitleQuery): List<String> {
        val primary = query.primaryTitle
        val words = primary.split(' ').filter { it.isNotBlank() }
        return buildList {
            addAll(query.titles)
            if (words.size > 2) add(words.take(2).joinToString(" "))
            words.firstOrNull()?.let { add(it) }
        }
            .map { it.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
    }

    private suspend fun seasonHtml(client: OkHttpClient, url: String): String {
        pageCache[url]?.takeIf { System.currentTimeMillis() - it.atMs < CACHE_TTL_MS }
            ?.let { return it.html }
        val html = SubtitleHttp.text(client, url)
        pageCache[url] = CachedPage(System.currentTimeMillis(), html)
        return html
    }

    /**
     * Walks the language sections and keeps the rows whose episode range covers the one being
     * watched. A movie (no episode) keeps the first row of each language.
     */
    private fun parse(html: String, query: SubtitleQuery): List<SubtitleResult> {
        val blocks = LANG_BLOCK.findAll(html).toList()
        if (blocks.isEmpty()) return emptyList()

        val episode = query.episode
        val target = MatchTarget(titles = query.titles, year = query.year)
        val results = mutableListOf<SubtitleResult>()
        blocks.forEachIndexed { index, block ->
            val languageCode = block.groupValues[1]
            val languageName = block.groupValues[2]
            val end = blocks.getOrNull(index + 1)?.range?.first ?: html.length
            val section = html.substring(block.range.last + 1, end)

            var matchedForThisLanguage = false
            ROW.findAll(section).forEach { row ->
                if (matchedForThisLanguage) return@forEach
                val from = row.groupValues[2].toIntOrNull() ?: return@forEach
                val to = row.groupValues[3].toIntOrNull() ?: return@forEach
                val covers = if (episode == null) true else episode in from..to
                if (!covers) return@forEach

                val body = row.groupValues[4]
                val zip = ZIP_LINK.find(body)?.groupValues?.get(1) ?: return@forEach
                val title = TITLE.find(body)?.groupValues?.get(1)?.plainText()
                    ?: "${languageName} subtitles"

                // The row's own episode range decided coverage; the matcher only has to agree that
                // this release belongs to the show being watched.
                if (!TitleMatcher.match(target, MatchCandidate(title)).accepted) return@forEach

                results += SubtitleResult(
                    id = zip,
                    sourceId = id,
                    sourceName = name,
                    title = title,
                    language = SubtitleLanguages.of(languageCode.ifBlank { languageName }),
                    format = "srt",
                    downloadRef = zip,
                    detail = if (from == to) "E$from" else "E$from–E$to",
                    episode = episode,
                )
                matchedForThisLanguage = true
            }
        }
        return results.distinctBy { "${it.language.code}|${it.title.lowercase(Locale.ROOT)}" }
    }
}
