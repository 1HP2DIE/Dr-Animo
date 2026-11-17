package com.animo.app.ui.player

import androidx.compose.runtime.Immutable
import com.animo.app.player.PlayerController

/**
 * Everything the player's settings sheet renders, in one immutable snapshot.
 *
 * The sheet is deliberately dumb: it takes this value plus callbacks and knows nothing about Media3.
 * The engine side ([com.animo.app.player.PlayerController]) publishes it.
 */
@Immutable
data class PlayerTuning(
    val qualities: List<QualityRow> = emptyList(),
    val activeQualityId: String = PlayerController.AUTO_QUALITY_ID,
    val audios: List<TrackRow> = emptyList(),
    val selectedAudioId: String = TrackRow.AUTO_ID,
    val texts: List<TrackRow> = emptyList(),
    val selectedTextId: String = TrackRow.OFF_ID,
    val speed: Float = 1f,
    /** True once the engine has published at least one real rendition. */
    val hasQualities: Boolean = false,
    /** True when the container has more than one audio track worth switching between. */
    val hasAudioTracks: Boolean = false,
)

/** One selectable rendition. [id] is the engine's stable key. */
@Immutable
data class QualityRow(
    val id: String,
    val label: String,
    val height: Int,
    val bitrate: Int,
)

/** One selectable audio or subtitle track. */
@Immutable
data class TrackRow(
    val id: String,
    val label: String,
) {
    companion object {
        /** Engine value for "let the player decide" — the default audio track. */
        const val AUTO_ID = "auto"

        /** Engine value for "subtitles off". */
        const val OFF_ID = "off"

        /**
         * A side-loaded subtitle file. It is not a container track, so selecting it means "turn the
         * container's text track off and draw the file instead" — see the player screen's
         * `onSelectText`.
         */
        const val FILE_ID = "file"
    }
}

/** Player speed presets, in the order the sheet renders them. */
val PlayerSpeeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Sleep-timer presets in minutes; `0` means "end of episode". */
val PlayerSleepMinutes: List<Int> = listOf(15, 30, 45, 60, 0)

/** Format a speed for a chip: `1x`, `1.25x`, `0.5x`. */
fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x".replace(".0x", "x")
