@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animo.app.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.PlayableStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One selectable video rendition, read from the master playlist (`NAME` -> [label], `RESOLUTION`). */
data class QualityOption(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val selected: Boolean = false,
)

/** One selectable container track (audio or subtitle). [id] is stable for the life of the source. */
data class TrackOption(
    val id: String,
    val label: String,
) {
    companion object {
        /** "Let the player decide" — the default audio rendition. */
        const val AUTO_ID = "auto"

        /** Subtitles off. The default, because nothing we ship carries text tracks yet. */
        const val OFF_ID = "off"
    }
}

/**
 * A resolved stream the host should switch to (SUB/DUB changes need a whole new [PlayableStream] —
 * a different `fileId` and therefore a different master playlist).
 */
data class StreamSwap(
    val id: Long,
    val stream: PlayableStream,
    val positionMs: Long,
)

/**
 * Per-player-route controller: quality menu, audio (SUB/DUB) switching, resume position and
 * next/prev hooks.
 *
 * It never owns a player — [PlayerHost] attaches/detaches the `ExoPlayer` of the current source, so
 * swapping sources (audio change or 403 re-resolve) rebinds the same controller to a new player
 * instance instead of leaking the old one.
 *
 * [switchAudio] is supplied by the caller because a SUB->DUB change re-runs the repository/resolver
 * chain; the controller only decides *when* and keeps the resume position.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val scope: CoroutineScope,
    private val switchAudio: (suspend (AudioTrack) -> PlayableStream?)? = null,
    private val onNext: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
) {

    private val _qualities = MutableStateFlow<List<QualityOption>>(emptyList())
    val qualities: StateFlow<List<QualityOption>> = _qualities.asStateFlow()

    private val _activeQualityId = MutableStateFlow(AUTO_QUALITY_ID)
    val activeQualityId: StateFlow<String> = _activeQualityId.asStateFlow()

    /** Container audio tracks (a multi-audio HLS stream), not the SUB/DUB source choice. */
    private val _audios = MutableStateFlow<List<TrackOption>>(emptyList())
    val audios: StateFlow<List<TrackOption>> = _audios.asStateFlow()

    private val _selectedAudioTrackId = MutableStateFlow(TrackOption.AUTO_ID)
    val selectedAudioTrackId: StateFlow<String> = _selectedAudioTrackId.asStateFlow()

    /** Subtitle tracks present in the container. Empty for every source we ship today. */
    private val _texts = MutableStateFlow<List<TrackOption>>(emptyList())
    val texts: StateFlow<List<TrackOption>> = _texts.asStateFlow()

    private val _selectedTextId = MutableStateFlow(TrackOption.OFF_ID)
    val selectedTextId: StateFlow<String> = _selectedTextId.asStateFlow()

    private val _activeAudio = MutableStateFlow<AudioTrack?>(null)
    val activeAudio: StateFlow<AudioTrack?> = _activeAudio.asStateFlow()

    private val _switchingAudio = MutableStateFlow(false)
    val switchingAudio: StateFlow<Boolean> = _switchingAudio.asStateFlow()

    private val _audioError = MutableStateFlow<ResolveException?>(null)
    val audioError: StateFlow<ResolveException?> = _audioError.asStateFlow()

    private val _pendingSwap = MutableStateFlow<StreamSwap?>(null)
    val pendingSwap: StateFlow<StreamSwap?> = _pendingSwap.asStateFlow()

    private val _resumePositionMs = MutableStateFlow(0L)
    val resumePositionMs: StateFlow<Long> = _resumePositionMs.asStateFlow()

    var durationMs: Long = 0L
        private set

    private var player: Player? = null
    private var audioJob: Job? = null

    /**
     * Track ids are not usable as `TrackSelectionOverride` arguments on their own, so the override
     * objects are captured while the tracks are known (they are only valid for one player instance).
     */
    private val audioOverrides = mutableMapOf<String, TrackSelectionOverride>()
    private val textOverrides = mutableMapOf<String, TrackSelectionOverride>()
    private val swapIds = AtomicLong(0L)

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            publishQualities(tracks)
            publishTracks(tracks)
        }
    }

    fun attach(player: Player) {
        if (this.player === player) return
        detach()
        this.player = player
        player.addListener(listener)
        publishQualities(player.currentTracks)
        publishTracks(player.currentTracks)
        applyQuality(player, _activeQualityId.value)
        // A new source resets Media3's track parameters, so the user's picks have to be re-applied.
        applyAudioTrack(player, _selectedAudioTrackId.value)
        applyTextTrack(player, _selectedTextId.value)
        _resumePositionMs.value = player.currentPosition.coerceAtLeast(0L)
    }

    /** Detaches only if [player] is the currently attached instance (or any player when null). */
    fun detach(player: Player? = null) {
        val attached = this.player ?: return
        if (player != null && player !== attached) return
        attached.removeListener(listener)
        this.player = null
        audioJob?.cancel()
        audioJob = null
        _switchingAudio.value = false
    }

    fun requestAudio(audio: AudioTrack) {
        if (_activeAudio.value == audio) return
        if (_switchingAudio.value) return
        val switcher = switchAudio
        if (switcher == null) {
            _audioError.value = ResolveException.Parse("audio switching is not available for this episode")
            return
        }
        audioJob = scope.launch {
            _switchingAudio.value = true
            _audioError.value = null
            try {
                val stream = switcher(audio)
                if (stream == null) {
                    _audioError.value = ResolveException.Dead("${audio.name.lowercase()} stream unavailable")
                } else {
                    _activeAudio.value = audio
                    _pendingSwap.value = StreamSwap(
                        id = swapIds.incrementAndGet(),
                        stream = stream,
                        positionMs = currentPositionMs(),
                    )
                }
            } catch (e: ResolveException) {
                _audioError.value = e
            } catch (e: Exception) {
                _audioError.value = ResolveException.Network(e)
            } finally {
                _switchingAudio.value = false
            }
        }
    }

    /** Called by the host after it applied a [pendingSwap] to a new player. */
    fun consumeSwap(id: Long) {
        val pending = _pendingSwap.value ?: return
        if (pending.id == id) _pendingSwap.value = null
    }

    fun setActiveAudio(audio: AudioTrack) {
        _activeAudio.value = audio
    }

    fun clearAudioError() {
        _audioError.value = null
    }

    /** Switches the container audio track. The SUB/DUB *source* switch is [requestAudio]. */
    fun selectAudioTrack(id: String) {
        _selectedAudioTrackId.value = id
        applyAudioTrack(player ?: return, id)
    }

    /** Switches (or clears) the subtitle track. */
    fun selectTextTrack(id: String) {
        _selectedTextId.value = id
        applyTextTrack(player ?: return, id)
    }

    private fun applyAudioTrack(player: Player, id: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == TrackOption.AUTO_ID) {
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        } else {
            val override = audioOverrides[id] ?: return
            builder.setOverrideForType(override)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun applyTextTrack(player: Player, id: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == TrackOption.OFF_ID) {
            builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val override = textOverrides[id] ?: return
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(override)
        }
        player.trackSelectionParameters = builder.build()
    }

    fun selectQuality(qualityId: String) {
        _activeQualityId.value = qualityId
        player?.let { applyQuality(it, qualityId) }
    }

    fun next() {
        onNext?.invoke()
    }

    fun prev() {
        onPrev?.invoke()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: _resumePositionMs.value

    /** Fed by the host's 1 s progress tick; keeps the resume position even mid-rebuffer. */
    fun noteProgress(positionMs: Long, durationMs: Long) {
        _resumePositionMs.value = positionMs.coerceAtLeast(0L)
        this.durationMs = durationMs.coerceAtLeast(0L)
    }

    private fun publishQualities(tracks: Tracks) {
        val options = buildList {
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    if (format.width <= 0 || format.height <= 0) continue
                    add(
                        QualityOption(
                            id = qualityId(format.width, format.height, format.bitrate),
                            label = format.label?.takeIf { it.isNotBlank() } ?: "${format.height}p",
                            width = format.width,
                            height = format.height,
                            bitrate = format.bitrate,
                            selected = group.isTrackSelected(trackIndex),
                        ),
                    )
                }
            }
        }.distinctBy { it.id }
            .sortedByDescending { it.height * 1_000_000L + it.bitrate }

        _qualities.value = options

        val pinned = _activeQualityId.value
        if (pinned != AUTO_QUALITY_ID && options.none { it.id == pinned }) {
            _activeQualityId.value = AUTO_QUALITY_ID
        }
    }

    /**
     * Reads the container's audio and subtitle groups. Both lists are usually empty on the sources we
     * ship (a single muxed audio track, no text), but the menu has to say that honestly rather than
     * showing an empty picker.
     */
    private fun publishTracks(tracks: Tracks) {
        val audios = mutableListOf<TrackOption>()
        val texts = mutableListOf<TrackOption>()
        audioOverrides.clear()
        textOverrides.clear()

        var selectedAudioId = TrackOption.AUTO_ID
        var selectedTextId = TrackOption.OFF_ID

        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val id = format.id ?: "$groupIndex:$trackIndex"
                val option = TrackOption(id = id, label = trackLabel(format, type, audios.size, texts.size))
                if (type == C.TRACK_TYPE_AUDIO) {
                    audios += option
                    audioOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                    if (group.isTrackSelected(trackIndex)) selectedAudioId = id
                } else {
                    texts += option
                    textOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                    if (group.isTrackSelected(trackIndex)) selectedTextId = id
                }
            }
        }

        _audios.value = audios.distinctBy { it.id }
        _texts.value = texts.distinctBy { it.id }
        // Only follow the stream while the user has not made a choice; a pin that disappeared with
        // the new source falls back to the engine's own answer.
        if (_selectedAudioTrackId.value == TrackOption.AUTO_ID ||
            audios.none { it.id == _selectedAudioTrackId.value }
        ) {
            _selectedAudioTrackId.value = selectedAudioId
        }
        if (_selectedTextId.value == TrackOption.OFF_ID ||
            texts.none { it.id == _selectedTextId.value }
        ) {
            _selectedTextId.value = selectedTextId
        }
    }

    private fun trackLabel(
        format: androidx.media3.common.Format,
        type: Int,
        audioIndex: Int,
        textIndex: Int,
    ): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { return it.uppercase(java.util.Locale.ROOT) }
        return if (type == C.TRACK_TYPE_AUDIO) "Audio ${audioIndex + 1}" else "Subtitle ${textIndex + 1}"
    }

    /**
     * Quality is applied through caps (`maxVideoSize` + `maxVideoBitrate`) rather than a hard track
     * override: the adaptive selector keeps working, which is what keeps a flaky rendition from
     * stalling playback.
     */
    private fun applyQuality(player: Player, qualityId: String) {
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (qualityId == AUTO_QUALITY_ID) {
            builder
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
        } else {
            val option = _qualities.value.firstOrNull { it.id == qualityId } ?: return
            builder
                .setMaxVideoSize(option.width, option.height)
                .setMaxVideoBitrate(option.bitrate)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun qualityId(width: Int, height: Int, bitrate: Int): String = "${width}x${height}@$bitrate"

    companion object {
        const val AUTO_QUALITY_ID: String = "auto"
    }
}
