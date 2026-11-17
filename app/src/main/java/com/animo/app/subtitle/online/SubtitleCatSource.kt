package com.animo.app.subtitle.online

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import com.animo.app.subtitle.SubtitleFile
import com.animo.app.subtitle.SubtitleFiles
import com.animo.app.subtitle.match.MatchCandidate
import com.animo.app.subtitle.match.MatchTarget
import com.animo.app.subtitle.match.TitleMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

/**
 * SubtitleCat — an aggregator that keeps one page per release and offers the same episode in ~20
 * languages, one direct `.srt` per language.
 *
 * Two requests deep, deliberately: the search page lists releases (episode inside the name), and
 * each release page lists the per-language files. Releases are chosen with [TitleMatcher], so
 * "Pokémon S16E05" cannot be accepted as an episode of "Pokémon Season 9" just because both are
 * episode 5.
 */
object SubtitleCatSource : OnlineSubtitleSource {

    override val id: String = "s1"
    override val name: String = "Source A"

    private val BASE: String get() = ProviderVault.url(ProviderKey.SUBTITLE_HOST_A)

    /** A handful of release pages is plenty; more only adds duplicate languages. */
    private const val MAX_RELEASE_PAGES = 4

    private val RELEASE_ROW = Regex("""<a href="(subs/\d+/[^"]+\.html)">([^<]+)</a>""")

    /**
     * The page marks each language file with `id="download_<lang>"`. The numeric folder in that href
     * is often *not* the page's own id, so the href has to be read, never rebuilt from the page id.
     */
    private val LANG_ANCHOR = Regex(
        """<a[^>]*?id="download_([A-Za-z0-9_-]{2,10})"[^>]*?href="([^"]+?\.srt)"""",
    )

    /** Fallback for pages without the anchors: the language is the last `-xx` before `.srt`. */
    private val SRT_LINK = Regex("""href="([^"]+?\.srt)"""")
    private val LANG_SUFFIX = Regex("""-([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,3})?)\.srt$""")

    override suspend fun search(query: SubtitleQuery, client: OkHttpClient): List<SubtitleResult> =
        coroutineScope {
            val searchHtml = SubtitleHttp.text(
                client,
                BASE + "index.php?search=" + query.freeText().urlEncoded(),
            )

            val releases = RELEASE_ROW.findAll(searchHtml)
                .map { match -> Release(href = match.groupValues[1], title = match.groupValues[2].plainText()) }
                .filter { it.title.isNotBlank() }
                .toList()

            if (releases.isEmpty()) return@coroutineScope emptyList()

            val target = MatchTarget(
                titles = query.titles,
                episode = query.episode,
                year = query.year,
            )
            val ranked = TitleMatcher.rank(target, releases.map { MatchCandidate(it.title) })
                .filter { it.accepted }

            if (ranked.isEmpty()) return@coroutineScope emptyList()

            val chosen = ranked
                .sortedWith(
                    compareByDescending<com.animo.app.subtitle.match.TitleMatch> {
                        query.episode != null && it.release.coversEpisode(query.episode)
                    }.thenByDescending { it.score },
                )
                .take(MAX_RELEASE_PAGES)
                .mapNotNull { match -> releases.firstOrNull { it.title == match.candidate.title } }

            val perPage = chosen.map { release ->
                async {
                    runCatching {
                        val html = SubtitleHttp.text(client, absolute(BASE, release.href))
                        languagesIn(html, release.title, query.episode)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()

            // One row per language, best release wins.
            val seen = mutableSetOf<String>()
            perPage.flatten().filter { seen.add(it.language.code) }
        }

    override suspend fun download(result: SubtitleResult, client: OkHttpClient): SubtitleFile {
        val bytes = SubtitleHttp.bytes(client, result.downloadRef, referer = BASE)
        val fileName = result.downloadRef.substringAfterLast('/')
        return SubtitleFiles.decodePayload(bytes, fileName, preferEpisode = result.episode)
            ?: error("subtitle payload unreadable")
    }

    /**
     * Every language file on a release page: `download_<lang>` anchors first, and the older
     * `<source>-<target>.srt` filenames as a fallback (`en-es-419` is Latin American Spanish).
     */
    private fun languagesIn(html: String, title: String, episode: Int?): List<SubtitleResult> {
        val prettyTitle = releaseDisplayName(title)

        val fromAnchors = LANG_ANCHOR.findAll(html).map { match ->
            val language = match.groupValues[1]
            val href = match.groupValues[2]
            SubtitleResult(
                id = href,
                sourceId = id,
                sourceName = name,
                title = prettyTitle,
                language = SubtitleLanguages.of(language),
                format = "srt",
                downloadRef = absolute(BASE, href),
                episode = episode,
            )
        }.toList()
        if (fromAnchors.isNotEmpty()) return fromAnchors

        return SRT_LINK.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1]
            val language = LANG_SUFFIX.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            SubtitleResult(
                id = href,
                sourceId = id,
                sourceName = name,
                title = prettyTitle,
                language = SubtitleLanguages.of(language),
                format = "srt",
                downloadRef = absolute(BASE, href),
                episode = episode,
            )
        }.toList()
    }

    /** `Frieren.Beyond.Journeys.End.S01E05.JAPANESE.WEBRip` → a readable row title. */
    private fun releaseDisplayName(title: String): String = title
        .replace('.', ' ')
        .replace(Regex("""(?i)\b(480|540|720|1080|2160)p\b"""), "")
        .replace(Regex("""(?i)\b(web-?rip|web-?dl|bluray|bdrip|hdrip|hdtv|x264|x265|h264|h265|hevc|av1|aac(\d\.\d)?|10bit)\b"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    private data class Release(val href: String, val title: String)
}
