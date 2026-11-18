package com.animo.app.download

import com.animo.app.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED }

/** One downloaded (or downloading) episode. The id is `episodeId:AUDIO`, one file per pair. */
@Serializable
data class DownloadEntry(
    val id: String,
    val animeId: Int,
    val animeTitle: String,
    val episodeId: Long,
    val episodeNumber: Int,
    val episodeTitle: String,
    val audio: String,
    /** Server number the viewer picked; `null` = automatic order. */
    val server: Int? = null,
    /** Language id on that server (`sub`, `dub`, `hi`…); `null` = the server's default for [audio]. */
    val languageId: String? = null,
    /** Human label of the language, e.g. `Hindi`; empty = plain SUB/DUB. */
    val languageLabel: String = "",
    /** Subtitle track label to save next to the video; `null` = automatic, empty = none. */
    val subtitleLabel: String? = null,
    /** Height the viewer asked for; `Int.MAX_VALUE` = highest. */
    val targetHeight: Int,
    /** What was actually downloaded, e.g. `720p`; empty until known. */
    val qualityLabel: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDone: Long = 0L,
    /** Best estimate of the final size; 0 when unknown. */
    val bytesTotal: Long = 0L,
    val progress: Float = 0f,
    /** Smoothed bytes per second while running; 0 when idle or unknown. */
    val speedBps: Long = 0L,
    val filePath: String? = null,
    val subtitlePath: String? = null,
    val error: String? = null,
    val createdAtMs: Long = 0L,
) {
    val file: File? get() = filePath?.let(::File)
    val isFinished: Boolean get() = status == DownloadStatus.DONE && file?.exists() == true

    /** Seconds left at the current speed, or `null` when it cannot be estimated yet. */
    val secondsLeft: Long?
        get() = if (speedBps > 0 && bytesTotal > bytesDone) (bytesTotal - bytesDone) / speedBps else null

    companion object {
        /** [key] is `SUB`/`DUB` for the original and English audio, `lang-<code>` for any other. */
        fun idOf(episodeId: Long, key: String): String = "$episodeId:$key"
    }
}

/**
 * The download list, one JSON file in app storage. Small (one row per episode) and written only on
 * state changes and throttled progress, so a database table would be ceremony without benefit.
 */
class DownloadStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(DownloadEntry.serializer())
    private val mutex = Mutex()

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    fun get(id: String): DownloadEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun upsert(entry: DownloadEntry) = mutate { list ->
        if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) entry else it } else list + entry
    }

    suspend fun update(id: String, change: (DownloadEntry) -> DownloadEntry) = mutate { list ->
        list.map { if (it.id == id) change(it) else it }
    }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(change: (List<DownloadEntry>) -> List<DownloadEntry>) {
        mutex.withLock {
            val next = change(_entries.value)
            _entries.value = next
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(serializer, next))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { AppLog.w("download list write failed", it) }
        }
    }

    private fun load(): List<DownloadEntry> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(serializer, file.readText())
            // A process death mid-download leaves RUNNING behind; the worker re-marks it when it resumes.
            .map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED, speedBps = 0L) else it }
    }.getOrElse {
        AppLog.w("download list unreadable", it)
        emptyList()
    }
}

/** `1.4 MB/s`, `320 KB/s`. */
fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1L shl 20 -> "%.1f MB/s".format(bytesPerSecond / (1L shl 20).toDouble())
    else -> "%d KB/s".format((bytesPerSecond shr 10).coerceAtLeast(1))
}

/** `2 h 05 min`, `13 min`, `40 s`. */
fun formatTimeLeft(seconds: Long): String = when {
    seconds >= 3600 -> "%d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60 -> "%d min".format((seconds + 30) / 60)
    else -> "%d s".format(seconds)
}
