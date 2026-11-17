package com.animo.app.subtitle

import kotlinx.serialization.Serializable

/** How a subtitle line aligns horizontally. Used by both the parser and the user's style. */
@Serializable
enum class CueAlignment { START, CENTER, END }

/**
 * One timed subtitle line.
 *
 * Container tracks do not appear here: Media3 hands over the cues that are on screen *right now*,
 * without timing, so every cue in this model came from a file the viewer loaded (or a link they
 * pasted).
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val alignment: CueAlignment = CueAlignment.CENTER,
) {
    fun isVisibleAt(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/** Longest gap we look backwards for a cue that is still on screen (overlapping dialogue). */
private const val MAX_LOOKBACK_MS = 30_000L

/**
 * The cues that should be visible at [positionMs].
 *
 * Binary search for the last cue that started, then a bounded walk back so an unusually long line
 * (a sign or a song lyric) still shows while a shorter cue after it has already ended. The walk is
 * capped at 64 cues: a file with more simultaneous dialogue than that is not something to optimise
 * for, and the cap keeps this allocation-free in the common case.
 */
fun List<SubtitleCue>.activeAt(positionMs: Long): List<SubtitleCue> {
    if (isEmpty()) return emptyList()

    var low = 0
    var high = size - 1
    var anchor = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
            anchor = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (anchor < 0) return emptyList()

    val hits = ArrayList<SubtitleCue>(2)
    var index = anchor
    var steps = 0
    while (index >= 0 && steps < 64) {
        val cue = this[index]
        if (positionMs - cue.startMs > MAX_LOOKBACK_MS) break
        if (cue.endMs > positionMs) hits += cue
        index--
        steps++
    }
    return if (hits.size <= 1) hits else hits.sortedBy { it.startMs }
}
