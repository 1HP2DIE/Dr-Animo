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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var headers: Map<String, String> = emptyMap()

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
        cells.value = emptyList()
        sprites.clear()
        loading.clear()
        val indexUrl = stream?.previewVtt ?: return
        headers = mapOf("Referer" to stream.referer, "User-Agent" to stream.userAgent)
        val parsed = withContext(Dispatchers.IO) {
            runCatching { PreviewVtt.parse(fetch(indexUrl).decodeToString(), indexUrl) }.getOrDefault(emptyList())
        }
        cells.value = parsed
        // The first sheet covers the opening minutes, which is where most scrubbing starts.
        parsed.firstOrNull()?.let { requestSprite(it.imageUrl) }
    }

    private fun requestSprite(url: String) {
        if (!loading.add(url)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = fetch(url)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) sprites[url] = bitmap else loading.remove(url)
        }
    }

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> if (value.isNotBlank()) header(name, value) }
        }.build()
        ServiceLocator.httpClient.newCall(request).execute().use { response ->
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
