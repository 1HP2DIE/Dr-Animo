package com.animo.app.ui.player

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.PlayableStream
import com.animo.app.player.PreviewCell
import com.animo.app.player.PreviewVtt
import com.animo.app.player.cancelOnCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Seek-preview frames for the scrub bubble.
 *
 * The source publishes a WebVTT index (one cue per 5 s) pointing into sprite sheets of 160×90
 * frames, 99 per sheet. The index is fetched once per stream; a sheet is fetched the first time the
 * finger lands in its range and kept for the rest of the playback — an episode is three sheets, so
 * there is nothing to evict. Both hosts gate on the stream's referer.
 */
@Stable
class SeekPreviewState internal constructor(
    private val scope: CoroutineScope,
) {
    private val cells = mutableStateOf<List<PreviewCell>>(emptyList())
    private val sprites = mutableStateMapOf<String, ImageBitmap>()
    private val loading = HashSet<String>()
    private val spriteJobs = mutableMapOf<String, Job>()
    private var headers: Map<String, String> = emptyMap()

    /**
     * Bumped for every stream. Work started for the previous episode must not write into the new
     * one's state, and a load that was cancelled mid-flight must not publish its result.
     */
    private var generation = 0

    val available: Boolean get() = cells.value.isNotEmpty()

    /** The frame for [positionMs], or `null` while its sheet is still downloading. */
    fun frameAt(positionMs: Long): Pair<ImageBitmap, PreviewCell>? {
        val cell = PreviewVtt.at(cells.value, positionMs) ?: return null
        val sprite = sprites[cell.imageUrl]
        if (sprite == null) {
            requestSprite(cell.imageUrl)
            return null
        }
        return sprite to cell
    }

    internal suspend fun load(stream: PlayableStream?) {
        val gen = ++generation
        // Sheets already on the wire belong to the episode that is leaving.
        spriteJobs.values.forEach { it.cancel() }
        spriteJobs.clear()
        cells.value = emptyList()
        sprites.clear()
        loading.clear()
        val indexUrl = stream?.previewVtt ?: return
        headers = mapOf("Referer" to stream.referer, "User-Agent" to stream.userAgent)
        // `runCatching` stays inside the IO block: outside it would also swallow the
        // CancellationException raised when the effect restarts, and then publish stale cells.
        val parsed = withContext(Dispatchers.IO) {
            runCatching { PreviewVtt.parse(fetch(indexUrl).decodeToString(), indexUrl) }.getOrElse { emptyList() }
        }
        currentCoroutineContext().ensureActive()
        if (gen != generation) return
        cells.value = parsed
        // The first sheet covers the opening minutes, which is where most scrubbing starts.
        parsed.firstOrNull()?.let { requestSprite(it.imageUrl) }
    }

    private fun requestSprite(url: String) {
        if (!loading.add(url)) return
        val gen = generation
        spriteJobs[url] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = fetch(url)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            spriteJobs.remove(url)
            if (gen != generation) return@launch
            if (bitmap != null) sprites[url] = bitmap else loading.remove(url)
        }
    }

    private suspend fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> if (value.isNotBlank()) header(name, value) }
        }.build()
        val call = ServiceLocator.httpClient.newCall(request)
        call.cancelOnCancellation()
        call.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }
}

@Composable
internal fun rememberSeekPreview(stream: PlayableStream?): SeekPreviewState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { SeekPreviewState(scope) }
    LaunchedEffect(stream?.previewVtt) { state.load(stream) }
    return state
}
