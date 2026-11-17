package com.animo.app.subtitle.online

import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import com.animo.app.subtitle.SubtitleFile
import com.animo.app.subtitle.SubtitleFiles
import com.animo.app.subtitle.isSubtitleFileName
import com.animo.app.subtitle.match.MatchCandidate
import com.animo.app.subtitle.match.ReleaseTitle
import com.animo.app.subtitle.match.MatchTarget
import com.animo.app.subtitle.match.TitleMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Kitsunekko — the largest free archive of **Japanese** subtitle files, one directory per anime.
 *
 * Its "API" is a directory listing. The index is a single ~600 KB page, so it is downloaded once
 * per process and cached for a few hours; searching a title then costs one more request for the
 * chosen folder.
 *
 * Folders are romaji while the app shows English titles, so the folder is chosen with
 * [TitleMatcher] — "Frieren: Beyond Journey's End" still finds "Sousou no Frieren" through the
 * anchor-word signal, and a folder for an unrelated show scores nothing.
 */
object KitsunekkoSource : OnlineSubtitleSource {

    override val id: String = "s3"
    override val name: String = "Source C"
    override val languages: Set<String> = setOf("ja")

    private val BASE: String get() = ProviderVault.url(ProviderKey.SUBTITLE_HOST_C)
    private const val ROOT_LISTING = "dirlist.php?dir=subtitles/japanese/"
    private const val INDEX_TTL_MS = 6 * 60 * 60 * 1000L

    private val FOLDER_ROW = Regex(
        """<a href="(/dirlist\.php\?dir=[^"]+)"[^>]*><strong>([^<]+)</strong>""",
    )
    private val FILE_ROW = Regex("""<a href="([^"]+\.(?:srt|ass|ssa|vtt))"[^>]*>(?:<strong>)?([^<]+)""")

    private val writeLock = Mutex()

    private data class Folder(val url: String, val name: String)

    private class Index(val atMs: Long, val folders: List<Folder>)

    @Volatile
    private var cached: Index? = null

    override suspend fun search(query: SubtitleQuery, client: OkHttpClient): List<SubtitleResult> =
        coroutineScope {
            val folders = index(client)
            if (folders.isEmpty()) return@coroutineScope emptyList()

            val target = MatchTarget(titles = query.titles, year = query.year)
            val ranked = folders
                .map { folder -> folder to TitleMatcher.match(target, MatchCandidate(folder.name)) }
                .filter { it.second.accepted }
                .sortedByDescending { it.second.score }
                .take(MAX_FOLDERS)

            if (ranked.isEmpty()) return@coroutineScope emptyList()

            val listings = ranked.map { (folder, _) ->
                async {
                    runCatching {
                        val html = SubtitleHttp.text(client, folder.url)
                        filesIn(html, folder, query.episode)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()

            listings.distinctBy { it.downloadRef }
        }

    override suspend fun download(result: SubtitleResult, client: OkHttpClient): SubtitleFile {
        val bytes = SubtitleHttp.bytes(client, result.downloadRef)
        val fileName = result.downloadRef.substringAfterLast('/')
        return SubtitleFiles.decodePayload(bytes, fileName, preferEpisode = result.episode)
            ?: error("subtitle payload unreadable")
    }

    private suspend fun index(client: OkHttpClient): List<Folder> {
        cached?.takeIf { System.currentTimeMillis() - it.atMs < INDEX_TTL_MS }?.let { return it.folders }
        return writeLock.withLock {
            cached?.takeIf { System.currentTimeMillis() - it.atMs < INDEX_TTL_MS }?.folders
                ?: run {
                    val html = SubtitleHttp.text(client, BASE + ROOT_LISTING)
                    val folders = FOLDER_ROW.findAll(html)
                        .mapNotNull { match ->
                            val href = match.groupValues[1]
                            val name = match.groupValues[2].plainText()
                            if (name.isBlank()) null else Folder(absolute(BASE, href), name)
                        }
                        .toList()
                    cached = Index(System.currentTimeMillis(), folders)
                    folders
                }
        }
    }

    private fun filesIn(html: String, folder: Folder, episode: Int?): List<SubtitleResult> =
        FILE_ROW.findAll(html)
            .mapNotNull { match ->
                val href = match.groupValues[1]
                val fileName = match.groupValues[2].plainText().trimStart('.')
                if (!isSubtitleFileName(fileName)) return@mapNotNull null
                if (episode != null) {
                    val parsed = ReleaseTitle.parse(fileName).episodes?.first
                        ?: fileName.substringBeforeLast('.').trim().toIntOrNull()
                    if (parsed != episode) return@mapNotNull null
                }
                val extension = fileName.substringAfterLast('.', "ass").lowercase(Locale.ROOT)
                SubtitleResult(
                    id = href,
                    sourceId = id,
                    sourceName = name,
                    title = fileName.substring(0, fileName.lastIndexOf('.')).trimStart('.'),
                    language = SubtitleLanguages.of("ja"),
                    format = extension,
                    downloadRef = absolute(BASE, href),
                    detail = folder.name,
                    episode = episode,
                )
            }
            .toList()

    private const val MAX_FOLDERS = 2
}
