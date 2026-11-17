package com.animo.app.player

import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.ServerLanguage
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.StreamServer
import com.animo.app.domain.repo.FallbackStreamSource
import com.animo.app.domain.repo.SourceRequest
import com.animo.app.domain.repo.wantsDub
import com.animo.app.provider.ProviderKey
import com.animo.app.provider.ProviderVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Server 3: the file mirror. See [MirrorProtocol] for the measured chain.
 *
 * Plays one MKV per episode that carries both the Japanese and the English audio, so SUB and DUB are
 * the same URL with a different preferred audio language. Opening/ending chapters, side-car
 * subtitles and the seek-preview index come from the embed's data payload. Hosts come from
 * [ProviderVault].
 */
class MirrorStreamSource(
    private val client: OkHttpClient,
    private val api: () -> String = { ProviderVault.optionalUrl(ProviderKey.MIRROR_API).orEmpty() },
    private val player: () -> String = { ProviderVault.optionalUrl(ProviderKey.MIRROR_PLAYER).orEmpty() },
    private val userAgent: String = Media3Factory.DEFAULT_USER_AGENT,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FallbackStreamSource {

    override val server: StreamServer = StreamServer.MIRROR

    private class Cached<T>(val atMs: Long, val value: T)

    private val serverCache = ConcurrentHashMap<String, Cached<List<MirrorProtocol.Server>>>()

    /** Never follows the download redirect: the signed URL it points at is what Media3 gets. */
    private val noRedirects: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override suspend fun languages(request: SourceRequest): List<ServerLanguage> = withContext(dispatcher) {
        val servers = runCatching { servers(request) }.getOrDefault(emptyList())
        buildList {
            if (servers.any { it.dataType == "sub" }) {
                add(ServerLanguage(id = "sub", label = "Original (SUB)", audio = AudioTrack.SUB, languageCode = "ja"))
            }
            if (servers.any { it.dataType == "dub" }) {
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
            val entry = MirrorProtocol.pick(servers(request), dub)
                ?: throw ResolveException.NotFound("no ${if (dub) "dub" else "sub"} on this server")
            val (aid, variant) = MirrorProtocol.embedId(entry.link)
                ?: throw ResolveException.Parse("unrecognised player link")
            val playerHost = player().trimEnd('/')
            val referer = "$playerHost/"

            val ticket = MirrorProtocol.ticket(get("$playerHost/d/$aid/__data.json", referer))
                ?: throw ResolveException.Parse("no download ticket")
            awaitReady(ticket, referer)
            val fileUrl = signedFileUrl(ticket, referer)

            // Chapters/subtitles/preview are a bonus: playback must not fail when they do.
            val extras = runCatching {
                MirrorProtocol.extras(get("$playerHost/e/$aid/__data.json?v=$variant", referer))
            }.getOrNull()

            PlayableStream(
                masterUrl = fileUrl,
                referer = referer,
                userAgent = userAgent,
                audio = if (dub) AudioTrack.DUB else AudioTrack.SUB,
                intro = extras?.intro,
                outro = extras?.outro,
                subtitles = extras?.subtitles.orEmpty(),
                previewVtt = extras?.previewVtt,
                container = StreamContainer.PROGRESSIVE,
                preferredAudioLanguage = if (dub) "eng" else "jpn",
            )
        }.recoverCatching { error ->
            throw when (error) {
                is ResolveException -> error
                is IOException -> ResolveException.Network(error)
                else -> ResolveException.Parse("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }

    private fun servers(request: SourceRequest): List<MirrorProtocol.Server> {
        val id = request.anilistId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ResolveException.NotFound("title has no AniList id")
        val number = request.episode.number
        val key = "$id:$number"
        serverCache[key]?.takeIf { now() - it.atMs < CACHE_TTL_MS }?.let { return it.value }

        val base = api().trimEnd('/').takeIf { it.isNotEmpty() }
            ?: throw ResolveException.NotFound("server not configured")
        val body = get(
            url = "$base/api/flix/$id/$number",
            referer = "$base/watch/$id?ep=$number",
            origin = base,
        )
        val servers = MirrorProtocol.servers(body)
        serverCache[key] = Cached(now(), servers)
        return servers
    }

    /**
     * The progress endpoint is a server-sent event stream: a ready file answers one event and
     * closes, a cold one keeps the connection open while it prepares. Lines are read as they arrive
     * and the whole wait is capped, so a cold file costs seconds, not a hung resolve.
     */
    private fun awaitReady(ticket: MirrorProtocol.FileTicket, referer: String) {
        val url = "${ticket.base}/download/${ticket.fileId}/progress?token=${ticket.token}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/event-stream")
            .header("Referer", referer)
            .build()
        val ready = runCatching {
            progressClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val source = response.body?.source() ?: return@use false
                while (true) {
                    val line = source.readUtf8Line() ?: return@use false
                    if (MirrorProtocol.isReady(line)) return@use true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }.getOrDefault(false)
        if (!ready) throw ResolveException.NotFound("file is still being prepared")
    }

    private val progressClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(PROGRESS_WAIT_S, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun signedFileUrl(ticket: MirrorProtocol.FileTicket, referer: String): String {
        val file = URLEncoder.encode(ticket.filename, "UTF-8").replace("+", "%20")
        val url = "${ticket.base}/download/${ticket.fileId}?token=${ticket.token}&file=$file"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Range", "bytes=0-0")
            .build()
        val signed = noRedirects.newCall(request).execute().use { response ->
            response.header("Location")?.takeIf { response.isRedirect }
                ?: if (response.isSuccessful) url else throw IOException("download HTTP ${response.code}")
        }
        ensureServable(signed, referer)
        return signed
    }

    /**
     * One byte from the signed file before Media3 gets it. Measured 2026-11-18: the progress stream
     * can say `ready` while the prepared copy has already expired, and then every signed URL for
     * that file answers 404 for minutes. Failing here turns that into "not on this server right
     * now" (so automatic order moves on) instead of a player error after the user waited.
     */
    private fun ensureServable(url: String, referer: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 410) {
                throw ResolveException.NotFound("the file host has not got this episode ready")
            }
            if (!response.isSuccessful) throw IOException("file HTTP ${response.code}")
        }
    }

    private fun get(url: String, referer: String, origin: String? = null): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/event-stream, */*")
            .header("Referer", referer)
            .apply { if (origin != null) header("Origin", origin) }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw ResolveException.NotFound()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val PROGRESS_WAIT_S = 12L
    }
}
