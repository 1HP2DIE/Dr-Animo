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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
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
 * no storage permission needed and removed with the app. Android 11+ hides that folder from USB and
 * from the system file picker, so "open in another player" goes through a FileProvider grant — that
 * is the only way a file leaves the app, which is why MediaStore (a copy, plus a phantom after
 * uninstall) was not worth it.
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

    init {
        // A queued row must always have a job behind it. `KEEP` leaves an existing job alone, so
        // this only revives rows whose job was lost (an interrupted server switch, a cleared queue).
        store.entries.value
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
            .forEach { schedule(it.id) }
    }

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
        // A screen's scope dies on back; a queued row must still reach WorkManager and the disk.
        withContext(Dispatchers.IO + NonCancellable) {
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
    }

    /**
     * Makes the next in-app playback of this episode use [entry]'s file even when it is not plain
     * SUB/DUB (a Hindi download, say): the Downloads screen calls it right before opening the player.
     */
    fun pinForPlayback(entry: DownloadEntry) {
        pinned[entry.episodeId] = entry.id
    }

    private val pinned = java.util.concurrent.ConcurrentHashMap<Long, String>()

    suspend fun retry(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        schedule(id, replace = true)
    }

    /** Cancels a running download and deletes whatever it wrote. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO + NonCancellable) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        // Files first, row second: a row that points at deleted files can only be deleted again,
        // while an orphaned file would never be reachable from the UI.
        store.get(id)?.let(::removeFiles)
        store.remove(id)
    }

    private fun removeFiles(entry: DownloadEntry) {
        entry.file?.delete()
        entry.subtitlePath?.let { File(it).delete() }
        // Unfinished pieces are named after the entry and its audio, whatever quality they got.
        val stem = "${filePrefix(entry)} [${audioLabel(entry)} "
        folderFor(entry).listFiles()?.forEach { file ->
            if (file.name.startsWith(stem) || file.name.startsWith(".$stem")) file.deleteRecursively()
        }
        folderFor(entry).takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    /** Servers that have [entry]'s language for its episode, for the "change server" picker. */
    suspend fun sourcesFor(entry: DownloadEntry): List<LanguageSource> {
        val episode = animeRepository.observeEpisodes(entry.animeId).first().firstOrNull { it.id == entry.episodeId }
            ?: return emptyList()
        val key = entry.id.substringAfter(':')
        return languages(entry.animeId, episode).firstOrNull { it.key == key }?.sources.orEmpty()
            .filter { it.server != null }
    }

    /**
     * Starts [id] again on another server. Pieces from different servers never mix (each server
     * has its own encode, so byte N or segment N is a different moment), so the old file goes.
     */
    suspend fun changeServer(id: String, source: LanguageSource) = withContext(Dispatchers.IO + NonCancellable) {
        stopAndWait(id)
        store.get(id)?.let { entry ->
            removeFiles(entry)
            store.update(id) {
                it.restartOn(source, note = null)
            }
            schedule(id, replace = true)
        }
    }

    /**
     * Cancels the job for [id] and waits (briefly) until it has really stopped. Cancelling is
     * asynchronous: without the wait, the old job can still write a last piece into the file the
     * new server is about to start, and a `KEEP` enqueue would be dropped as a duplicate.
     */
    private suspend fun stopAndWait(id: String) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(workName(id))
        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            work.getWorkInfosForUniqueWorkFlow(workName(id)).first { infos ->
                infos.none { it.state == androidx.work.WorkInfo.State.RUNNING }
            }
        }
    }

    private fun DownloadEntry.restartOn(source: LanguageSource, note: String?) = copy(
        server = source.server?.number,
        languageId = source.languageId,
        status = DownloadStatus.QUEUED,
        progress = 0f,
        bytesDone = 0L,
        bytesTotal = 0L,
        speedBps = 0L,
        qualityLabel = "",
        filePath = null,
        subtitlePath = null,
        error = null,
        note = note,
        runToken = System.nanoTime(),
    )

    /**
     * Called by [DownloadWorker]. When a server fails, the download starts over on the next server
     * that has the same language, until one works or none is left. Returns false on final failure.
     */
    internal suspend fun run(id: String, onProgress: (DownloadEntry) -> Unit): Boolean {
        val tried = mutableSetOf<Int>()
        while (true) {
            val token = store.get(id)?.runToken ?: return true
            when (attempt(id, onProgress)) {
                Attempt.DONE -> return true
                // A full disk, an unmounted card: another server would fail the same way, after
                // downloading the whole episode again.
                Attempt.LOCAL_FAILED -> return false
                Attempt.SERVER_FAILED -> Unit
            }
            // Cancelled, or another server was picked meanwhile: that run owns the row now.
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return false
            val failed = store.get(id)?.takeIf { it.runToken == token } ?: return false
            failed.server?.let(tried::add)
            val next = runCatching { sourcesFor(failed) }.getOrDefault(emptyList())
                .firstOrNull { it.server != null && it.server.number !in tried }
                ?: return false
            AppLog.i("download $id: Server ${failed.server} failed, switching to ${next.server?.label}")
            removeFiles(failed)
            store.update(id) {
                // Somebody may have picked another server while this attempt was finishing; that
                // choice wins over the fallback.
                if (it.runToken == token) {
                    it.restartOn(next, note = "Server ${failed.server ?: "?"} failed · switched to ${next.server?.label}")
                } else {
                    it
                }
            }
        }
    }

    private enum class Attempt { DONE, SERVER_FAILED, LOCAL_FAILED }

    private suspend fun attempt(id: String, onProgress: (DownloadEntry) -> Unit): Attempt {
        val entry = store.get(id) ?: return Attempt.DONE
        if (entry.isFinished) return Attempt.DONE
        val token = entry.runToken
        // Every write below goes through this, so a superseded job cannot touch the new run's row.
        suspend fun mine(change: (DownloadEntry) -> DownloadEntry) =
            store.update(id) { if (it.runToken == token) change(it) else it }
        mine { it.copy(status = DownloadStatus.RUNNING, error = null, speedBps = 0L) }
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
            // Automatic order may land anywhere; remember where, so a failure moves on from there.
            stream.server?.let { actual -> mine { it.copy(server = actual.number) } }
            val serverTag = (stream.server?.number ?: entry.server)?.let { " S$it" }.orEmpty()
            val result = engine.download(
                stream = stream,
                targetHeight = entry.targetHeight,
                directory = folderFor(entry),
                // The server is in the name, so a late write from a job on another server never lands
                // in this run's file or its resume ledger.
                baseName = { quality -> "${filePrefix(entry)} [${audioLabel(entry)} $quality$serverTag]" },
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
                    mine {
                        // A range that failed after counting its bytes can report past 100%; the bar
                        // must never draw beyond its track.
                        it.copy(
                            bytesDone = done,
                            bytesTotal = total,
                            progress = fraction.coerceIn(0f, 1f),
                            speedBps = speed.toLong(),
                        )
                    }
                    store.get(id)?.let(onProgress)
                },
            )
            mine {
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
            Attempt.DONE
        } catch (cancelled: CancellationException) {
            // Deleted or stopped by the system: WorkManager reschedules a stopped job by itself.
            store.get(id)?.let { current ->
                if (current.status == DownloadStatus.RUNNING) mine { it.copy(status = DownloadStatus.QUEUED) }
            }
            throw cancelled
        } catch (error: Throwable) {
            AppLog.w("download $id failed", error)
            mine { it.copy(status = DownloadStatus.FAILED, error = describe(error), speedBps = 0L) }
            if (error.isServerProblem()) Attempt.SERVER_FAILED else Attempt.LOCAL_FAILED
        }
    }

    /**
     * Whether another server has a chance. A refused file, a dead host or a truncated body is the
     * server's fault; a full disk or a vanished folder is ours, and retrying it elsewhere would only
     * burn the same traffic again.
     */
    private fun Throwable.isServerProblem(): Boolean = when (this) {
        is DownloadException -> true
        is java.io.FileNotFoundException -> false
        is SecurityException -> false
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        is java.net.SocketException,
        is java.io.InterruptedIOException,
        is javax.net.ssl.SSLException,
        -> true
        is java.io.IOException -> message?.let { text ->
            text.startsWith("HTTP ") || text.startsWith("short ") || text.startsWith("range ")
        } ?: false
        else -> false
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

    private fun schedule(id: String, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
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
