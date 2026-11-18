package com.animo.app.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.animo.app.AppLog
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import com.animo.app.domain.model.PlayableStream
import com.animo.app.domain.model.StreamContainer
import com.animo.app.domain.model.StreamSubtitle
import com.animo.app.domain.repo.AnimeRepository
import com.animo.app.domain.repo.StreamRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Everything about downloads in one place: the list, the queue (WorkManager, one unique job per
 * episode so a double tap cannot download twice), offline playback and handing a file to another
 * player.
 *
 * Files live in the app's own folder on shared storage (`Android/data/<app>/files/Movies/Animo`):
 * no storage permission needed, visible to a file manager over USB, removed with the app.
 */
class DownloadCenter(
    private val context: Context,
    private val animeRepository: AnimeRepository,
    /** The network resolver, never the offline-first one: a download must fetch the real stream. */
    private val streamRepository: () -> StreamRepository,
    client: OkHttpClient,
) {
    private val store = DownloadStore(File(context.filesDir, "downloads.json"))
    private val engine = DownloadEngine(client)

    val entries: StateFlow<List<DownloadEntry>> = store.entries

    fun entry(episodeId: Long, audio: AudioTrack): DownloadEntry? =
        store.get(DownloadEntry.idOf(episodeId, audio.name))

    /** Every download of [episodeId], whatever its language. */
    fun entriesFor(episodeId: Long): List<DownloadEntry> = entries.value.filter { it.episodeId == episodeId }

    /**
     * Every language any server offers for [episode], merged across servers: "Japanese" on Server 1
     * and Server 2 is one choice with two sources. Original and English keep the SUB/DUB keys the
     * rest of the app uses; anything else (Hindi, Tamil…) gets its own.
     */
    suspend fun languages(animeId: Int, episode: Episode): List<DownloadLanguage> {
        val options = runCatching { streamRepository().serverOptions(animeId, episode) }.getOrDefault(emptyList())
        val merged = linkedMapOf<String, DownloadLanguage>()
        options.filter { it.reachable }.forEach { option ->
            option.languages.forEach { language ->
                val key = keyOf(language.audio, language.languageCode)
                val source = LanguageSource(option.server, language.id)
                val current = merged[key]
                merged[key] = current?.copy(sources = current.sources + source)
                    ?: DownloadLanguage(key, labelOf(key, language.label), language.audio, listOf(source))
            }
        }
        if (merged.isEmpty()) {
            if (episode.hasAudio(AudioTrack.SUB)) merged["SUB"] = DownloadLanguage("SUB", "Japanese (SUB)", AudioTrack.SUB, listOf(LanguageSource(null, null)))
            if (episode.hasAudio(AudioTrack.DUB)) merged["DUB"] = DownloadLanguage("DUB", "English (DUB)", AudioTrack.DUB, listOf(LanguageSource(null, null)))
        }
        val order = listOf("SUB", "DUB")
        return merged.values.sortedBy { order.indexOf(it.key).takeIf { i -> i >= 0 } ?: order.size }
    }

    /** Qualities and subtitle tracks one server has for one language. Resolving is the slow part. */
    suspend fun probe(animeId: Int, episode: Episode, language: DownloadLanguage, source: LanguageSource): Result<Probe> =
        streamRepository().resolve(animeId, episode, language.audio, source.server, source.languageId).mapCatching { stream ->
            Probe(
                source = source,
                qualities = engine.options(stream),
                subtitles = stream.subtitles.map { it.label }.distinct(),
                defaultSubtitle = engine.defaultSubtitle(stream),
            )
        }

    suspend fun enqueue(anime: Anime, episode: Episode, request: DownloadRequest) {
        val id = DownloadEntry.idOf(episode.id, request.language.key)
        val previous = store.get(id)
        if (previous != null && (previous.isFinished || previous.status == DownloadStatus.RUNNING)) return
        store.upsert(
            DownloadEntry(
                id = id,
                animeId = anime.id,
                animeTitle = anime.title,
                episodeId = episode.id,
                episodeNumber = episode.number,
                episodeTitle = episode.title,
                audio = request.language.audio.name,
                server = request.source.server?.number,
                languageId = request.source.languageId,
                languageLabel = if (request.language.key in setOf("SUB", "DUB")) "" else request.language.label,
                subtitleLabel = request.subtitleLabel,
                targetHeight = request.targetHeight,
                createdAtMs = previous?.createdAtMs ?: System.currentTimeMillis(),
            ),
        )
        schedule(id)
    }

    /**
     * Makes the next in-app playback of this episode use [entry]'s file even when it is not plain
     * SUB/DUB (a Hindi download, say): the Downloads screen calls it right before opening the player.
     */
    fun pinForPlayback(entry: DownloadEntry) {
        pinned[entry.episodeId] = entry.id
    }

    private val pinned = java.util.concurrent.ConcurrentHashMap<Long, String>()

    suspend fun retry(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        schedule(id)
    }

    /** Cancels a running download and deletes whatever it wrote. */
    suspend fun delete(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        store.get(id)?.let { entry ->
            entry.file?.delete()
            entry.subtitlePath?.let { File(it).delete() }
            // Unfinished pieces are named after the entry and its audio, whatever quality they got.
            val stem = "${filePrefix(entry)} [${audioLabel(entry)} "
            folderFor(entry).listFiles()?.forEach { file ->
                if (file.name.startsWith(stem) || file.name.startsWith(".$stem")) {
                    file.deleteRecursively()
                }
            }
            folderFor(entry).takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        }
        store.remove(id)
    }

    /** Called by [DownloadWorker]. Returns false when the download failed (the entry says why). */
    internal suspend fun run(id: String, onProgress: (DownloadEntry) -> Unit): Boolean {
        val entry = store.get(id) ?: return true
        if (entry.isFinished) return true
        store.update(id) { it.copy(status = DownloadStatus.RUNNING, error = null, speedBps = 0L) }
        // Speed: bytes over the last few seconds, smoothed so one stalled range does not make the
        // meter jump to zero and back.
        var sampleAt = System.currentTimeMillis()
        var sampleBytes = -1L
        var speed = 0.0
        return try {
            val audio = AudioTrack.entries.firstOrNull { it.name == entry.audio } ?: AudioTrack.SUB
            val episode = animeRepository.observeEpisodes(entry.animeId).first().firstOrNull { it.id == entry.episodeId }
                ?: throw DownloadException("the episode is no longer in the catalogue")
            val stream = streamRepository().resolve(
                entry.animeId,
                episode,
                audio,
                entry.server?.let { com.animo.app.domain.model.StreamServer.fromNumber(it) },
                entry.languageId,
            ).getOrThrow()
            val result = engine.download(
                stream = stream,
                targetHeight = entry.targetHeight,
                directory = folderFor(entry),
                baseName = { quality -> "${filePrefix(entry)} [${audioLabel(entry)} $quality]" },
                subtitleLabel = entry.subtitleLabel,
                onProgress = { done, total, fraction ->
                    val now = System.currentTimeMillis()
                    if (sampleBytes < 0) {
                        sampleBytes = done
                        sampleAt = now
                    } else if (now - sampleAt >= SPEED_WINDOW_MS) {
                        val instant = (done - sampleBytes).coerceAtLeast(0L) * 1000.0 / (now - sampleAt)
                        speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                        sampleBytes = done
                        sampleAt = now
                    }
                    store.update(id) {
                        it.copy(bytesDone = done, bytesTotal = total, progress = fraction, speedBps = speed.toLong())
                    }
                    store.get(id)?.let(onProgress)
                },
            )
            store.update(id) {
                it.copy(
                    status = DownloadStatus.DONE,
                    progress = 1f,
                    speedBps = 0L,
                    filePath = result.file.absolutePath,
                    subtitlePath = result.subtitle?.absolutePath,
                    qualityLabel = result.qualityLabel,
                    bytesDone = result.file.length(),
                    bytesTotal = result.file.length(),
                )
            }
            true
        } catch (cancelled: CancellationException) {
            // Deleted or stopped by the system: WorkManager reschedules a stopped job by itself.
            store.get(id)?.let { current ->
                if (current.status == DownloadStatus.RUNNING) store.update(id) { it.copy(status = DownloadStatus.QUEUED) }
            }
            throw cancelled
        } catch (error: Throwable) {
            AppLog.w("download $id failed", error)
            store.update(id) { it.copy(status = DownloadStatus.FAILED, error = describe(error), speedBps = 0L) }
            false
        }
    }

    /**
     * The downloaded file as a stream the app's own player can open, or `null` when this episode
     * and audio are not downloaded (or the file was removed behind the app's back).
     */
    fun localStream(episodeId: Long, audio: AudioTrack): PlayableStream? {
        val entry = pinned[episodeId]?.let(store::get)?.takeIf { it.isFinished && it.audio == audio.name }
            ?: entry(episodeId, audio)?.takeIf { it.isFinished }
            ?: return null
        val file = entry.file ?: return null
        val subtitle = entry.subtitlePath?.let(::File)?.takeIf { it.exists() }
        return PlayableStream(
            masterUrl = Uri.fromFile(file).toString(),
            referer = "",
            userAgent = "",
            audio = audio,
            container = StreamContainer.PROGRESSIVE,
            subtitles = listOfNotNull(
                subtitle?.let {
                    StreamSubtitle(
                        url = Uri.fromFile(it).toString(),
                        label = entry.subtitleLabel?.takeIf { it.isNotBlank() } ?: "Subtitles",
                        language = "eng",
                        isDefault = true,
                        forced = audio == AudioTrack.DUB,
                    )
                },
            ),
        )
    }

    /**
     * `ACTION_VIEW` for another player. The content URI is granted read access; the extras are the
     * ones VLC and MX Player read for a title and a side-car subtitle.
     */
    fun externalPlayerIntent(entry: DownloadEntry): Intent? {
        val file = entry.file?.takeIf { it.exists() } ?: return null
        val video = uriFor(file)
        val subtitle = entry.subtitlePath?.let(::File)?.takeIf { it.exists() }?.let(::uriFor)
        val title = "${entry.animeTitle} · E${entry.episodeNumber}"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(video, mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", title)
            if (subtitle != null) {
                clipData = ClipData.newRawUri(title, video).apply { addItem(ClipData.Item(subtitle)) }
                putExtra("subtitles_location", subtitle.toString())
                putExtra("subs", arrayOf(subtitle))
                putExtra("subs.enable", arrayOf(subtitle))
            }
        }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private fun schedule(id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    private fun root(): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies"))
            .resolve("Animo")

    private fun folderFor(entry: DownloadEntry): File =
        root().resolve(DownloadEngine.sanitize(entry.animeTitle).ifBlank { "Anime ${entry.animeId}" })

    private fun filePrefix(entry: DownloadEntry): String = DownloadEngine.sanitize(
        "E%02d %s".format(entry.episodeNumber, entry.episodeTitle).trim(),
    )

    private fun audioLabel(entry: DownloadEntry): String = when {
        entry.languageLabel.isNotBlank() -> DownloadEngine.sanitize(entry.languageLabel)
        entry.audio == AudioTrack.DUB.name -> "DUB"
        else -> "SUB"
    }

    private fun keyOf(audio: AudioTrack, code: String?): String {
        val iso = code?.lowercase()?.take(2)
        return when {
            audio == AudioTrack.SUB && (iso == null || iso == "ja") -> "SUB"
            audio == AudioTrack.DUB && (iso == null || iso == "en") -> "DUB"
            else -> "lang-${iso ?: audio.name.lowercase()}"
        }
    }

    private fun labelOf(key: String, label: String): String = when (key) {
        "SUB" -> "Japanese (SUB)"
        "DUB" -> "English (DUB)"
        else -> label
    }

    private fun describe(error: Throwable): String = when {
        error is DownloadException -> error.message.orEmpty()
        error is java.net.SocketTimeoutException || error.message?.contains("timeout", ignoreCase = true) == true ->
            "The server is very slow right now. Tap retry: the finished parts are kept."
        error is java.net.UnknownHostException || error is java.net.ConnectException ->
            "No connection to the server. Tap retry when you are online."
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun workName(id: String) = "download-$id"

    private companion object {
        const val SPEED_WINDOW_MS = 2_000L
    }
}

/** A source for one language: a server and its id for that language (`null`s = automatic order). */
data class LanguageSource(val server: com.animo.app.domain.model.StreamServer?, val languageId: String?)

data class DownloadLanguage(
    val key: String,
    val label: String,
    val audio: AudioTrack,
    val sources: List<LanguageSource>,
)

data class Probe(
    val source: LanguageSource,
    val qualities: List<QualityOption>,
    val subtitles: List<String>,
    val defaultSubtitle: String?,
)

data class DownloadRequest(
    val language: DownloadLanguage,
    val source: LanguageSource,
    val targetHeight: Int,
    /** `null` = automatic, empty = none. */
    val subtitleLabel: String?,
)
