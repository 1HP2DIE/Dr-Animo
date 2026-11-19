package com.animo.app.ui.common

import androidx.annotation.StringRes
import com.animo.app.R
import com.animo.app.domain.model.Anime
import com.animo.app.domain.model.AudioTrack
import com.animo.app.domain.model.Episode
import java.util.Locale

/** Player / progress clock: `12:04` under an hour, `1:12:04` above it. */
fun formatClock(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Two decimals, exactly like the API string, so `9.03` never renders as `9`. */
fun formatScore(score: Double?): String? =
    score?.let { String.format(Locale.US, "%.2f", it) }

/** Fallback glyph for a poster that failed or is missing. */
fun Anime.initials(): String {
    val words = title.trim().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    return words.take(2).map { it.first().uppercaseChar() }.joinToString("")
}

/** Episode titles are HTML-unescaped at ingest, but 5+ duplicates and blanks exist — always show the number. */
fun Episode.displayTitle(): String =
    if (title.isBlank()) "Episode $number" else title

@StringRes
fun AudioTrack.labelRes(): Int = when (this) {
    AudioTrack.SUB -> R.string.label_sub
    AudioTrack.DUB -> R.string.label_dub
}

/** Episode has a usable source for at least one audio track. */
fun Episode.isPlayable(): Boolean = !subUrl.isNullOrBlank() || !dubUrl.isNullOrBlank()

/**
 * The audio the player should start with: the user's preference when it exists for this episode,
 * otherwise whichever track the episode actually has. Never returns an unavailable track.
 */
/** True when this episode actually ships the requested track. */
fun Episode.supportsAudio(audio: AudioTrack): Boolean = when (audio) {
    AudioTrack.SUB -> !subUrl.isNullOrBlank()
    AudioTrack.DUB -> !dubUrl.isNullOrBlank()
}

fun Episode.pickAudio(preferred: AudioTrack): AudioTrack {
    val subAvailable = !subUrl.isNullOrBlank()
    val dubAvailable = !dubUrl.isNullOrBlank()
    return when {
        preferred == AudioTrack.SUB && subAvailable -> AudioTrack.SUB
        preferred == AudioTrack.DUB && dubAvailable -> AudioTrack.DUB
        subAvailable -> AudioTrack.SUB
        dubAvailable -> AudioTrack.DUB
        else -> preferred
    }
}
