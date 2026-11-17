package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.StreamAlternate
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.FallbackStreamSource
import com.animo.app.domain.repo.SourceRequest
import com.animo.app.domain.repo.wantsDub
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Wire format of the direct-file archive (Server 4), measured live on 2026-11-18. Pure JVM.
 *
 * ```
 * GET {host}/search/auto/?q={title}       → [{name, url:"/series/<slug>"}]
 * GET {host}/series/<slug>                → links "/<slug>-episode-<n>"
 * GET {host}/<slug>-episode-<n>           → <a data-version="subbed|dubbed" data-id="…">
 * GET {host}/embed/<id>                   → var videoSources = [{file:"/play/…", label:"720p"}, …]
 * GET {host}{file}  Referer: embed        → 302 → 302 → 206 video/mp4 (range-capable)
 * ```
 * The CDN answers 500 without the embed referer, and that header has to survive two redirects
 * (OkHttp keeps it; only `Authorization` is dropped across hosts).
 */
object ArchiveProtocol {

    data class Hit(val name: String, val path: String)

    data class Source(val path: String, val label: String, val height: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun searchHits(body: String): List<Hit> {
        val list = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val path = (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("/") }
                ?: return@mapNotNull null
            Hit(name, path)
        }
    }

    /**
     * The hit that *is* this title — exact match after normalising case and punctuation.
     *
     * Deliberately strict: the archive lists every season and spin-off as its own series, and a
     * near-match ("Frieren" vs "Frieren season 2") would play the wrong show, which is worse than
     * this server simply not having it.
     */
    fun match(hits: List<Hit>, titles: List<String>): Hit? {
        val wanted = titles.map(::normalize).filter { it.isNotEmpty() }.toSet()
        return hits.firstOrNull { normalize(it.name) in wanted }
    }

    fun normalize(title: String): String =
        title.lowercase().replace("&", "and").replace(Regex("""[^a-z0-9]+"""), "")

    /** Episode page path for episode [number] from the series page, or `null`. */
    fun episodePath(seriesHtml: String, number: Int): String? =
        EPISODE_LINK.findAll(seriesHtml)
            .firstOrNull { it.groupValues[2].toIntOrNull() == number }
            ?.groupValues?.get(1)

    /** `data-version` → embed id, e.g. `subbed` → `131519`. */
    fun versions(episodeHtml: String): Map<String, String> =
        VERSION_TAG.findAll(episodeHtml).mapNotNull { tag ->
            val text = tag.value
            val version = VERSION_ATTR.find(text)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
            val id = ID_ATTR.find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            version to id
        }.toMap()

    fun sources(embedHtml: String): List<Source> {
        val block = VIDEO_SOURCES.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        return SOURCE_ENTRY.findAll(block).mapNotNull { entry ->
            val path = entry.groupValues[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = entry.groupValues[2]
            Source(path, label, Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }.toList()
    }

    private val EPISODE_LINK = Regex("""href="(/[^"]*?-episode-(\d+))"""")
    private val VERSION_TAG = Regex("""<a[^>]*data-version=[^>]*>""")
    private val VERSION_ATTR = Regex("""data-version=["']([a-zA-Z]+)["']""")
    private val ID_ATTR = Regex("""data-id=["'](\d+)["']""")
    private val VIDEO_SOURCES = Regex("""var\s+videoSources\s*=\s*(\[[\s\S]*?]);""")
    private val SOURCE_ENTRY = Regex("""file:\s*"([^"]+)"\s*,\s*label:\s*"([^"]*)"""")
}

/**
 * Server 4: the direct-file archive. Found by title (it has no AniList/MAL ids), so it only answers
 * when a search hit matches the catalogue title exactly. Every quality is a separate MP4, published
 * as [StreamAlternate]s; the highest is played first. Host comes from [ProviderVault].
 */
class ArchiveStreamSource(
    private val client: OkHttpClient,
    private val host: () -> String = { ProviderVault.optionalUrl(ProviderKey.ARCHIVE_HOST).orEmpty() },
    private val userAgent: String = Media3Factory.DEFAULT_USER_AGENT,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FallbackStreamSource {

    override val server: StreamServer = StreamServer.ARCHIVE

    private class Cached<T>(val atMs: Long, val value: T)

    /** Episode page per `(titles, episode)`: both the menu probe and playback need it. */
    private val episodeCache = ConcurrentHashMap<String, Cached<Pair<String, Map<String, String>>?>>()

    override suspend fun languages(request: SourceRequest): List<ServerLanguage> = withContext(dispatcher) {
        val versions = runCatching { episode(request)?.second }.getOrNull().orEmpty()
        buildList {
            if ("subbed" in versions) {
                add(ServerLanguage(id = "sub", label = "Original (SUB)", audio = AudioTrack.SUB, languageCode = "ja"))
            }
            if ("dubbed" in versions) {
                add(ServerLanguage(id = "dub", label = "English (DUB)", audio = AudioTrack.DUB, languageCode = "en"))
            }
        }
    }

    override suspend fun resolve(
        request: SourceRequest,
        audio: AudioTrack,
        languageId: String?,
    ): Result<PlayableStream> = withContext(dispatcher) {
        runCatching {
            val dub = wantsDub(languageId, audio)
                ?: throw ResolveException.NotFound("that language is not on this server")
            val (pagePath, versions) = episode(request)
                ?: throw ResolveException.NotFound("title not on this server")
            val embedId = versions[if (dub) "dubbed" else "subbed"]
                ?: throw ResolveException.NotFound("no ${if (dub) "dub" else "sub"} for this episode")
            val base = base()
            val embedUrl = "$base/embed/$embedId"
            val sources = ArchiveProtocol.sources(get(embedUrl, referer = base + pagePath))
                .sortedByDescending { it.height }
            val best = sources.firstOrNull() ?: throw ResolveException.Dead("no files in the player")

            PlayableStream(
                masterUrl = base + best.path,
                // The CDN answers 500 without the embed as referer.
                referer = embedUrl,
                userAgent = userAgent,
                audio = if (dub) AudioTrack.DUB else AudioTrack.SUB,
                container = StreamContainer.PROGRESSIVE,
                alternates = sources.map { StreamAlternate(it.label, it.height, base + it.path) },
            )
        }.recoverCatching { error ->
            throw when (error) {
                is ResolveException -> error
                is IOException -> ResolveException.Network(error)
                else -> ResolveException.Parse("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }

    /** `(episode page path, version → embed id)`, or `null` when the archive does not have it. */
    private fun episode(request: SourceRequest): Pair<String, Map<String, String>>? {
        val titles = request.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (titles.isEmpty()) return null
        val key = titles.joinToString("|") + "#" + request.episode.number
        episodeCache[key]?.takeIf { now() - it.atMs < CACHE_TTL_MS }?.let { return it.value }

        val base = base()
        var hit: ArchiveProtocol.Hit? = null
        for (title in titles.take(2)) {
            val url = "$base/search/auto/".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", title)?.build()?.toString() ?: continue
            hit = ArchiveProtocol.match(ArchiveProtocol.searchHits(get(url, referer = "$base/")), titles)
            if (hit != null) break
        }
        val result = hit?.let { series ->
            val path = ArchiveProtocol.episodePath(get(base + series.path, referer = "$base/"), request.episode.number)
            path?.let { it to ArchiveProtocol.versions(get(base + it, referer = base + series.path)) }
        }
        episodeCache[key] = Cached(now(), result)
        return result
    }

    private fun base(): String = host().trimEnd('/').takeIf { it.isNotEmpty() }
        ?: throw ResolveException.NotFound("server not configured")

    private fun get(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ResolveException.NotFound()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
