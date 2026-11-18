package com.animo.app.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One episode slot on the weekly schedule. [airAtSec] is epoch seconds. */
data class AiringSlot(
    val anime: Anime,
    val airAtSec: Long,
    val episode: Int?,
    val favorite: Boolean,
) {
    val aired: Boolean get() = airAtSec * 1000 <= System.currentTimeMillis()
}

/** One day of the schedule, today first. */
data class AiringDay(
    val date: LocalDate,
    val dayOfWeek: DayOfWeek,
    val slots: List<AiringSlot>,
)

/**
 * Builds the next seven days of releases from what the catalogue knows: each airing title's next
 * air time. A weekly show airs on the same weekday at the same time, so a recorded time that has
 * already passed is moved forward a week at a time (and its episode number with it) until it lands
 * in the window. Titles without a recorded time cannot be placed and are left out.
 *
 * Pure: the clock and zone are arguments, so the projection is unit-testable.
 */
object AiringSchedule {

    private const val WEEK_SEC = 7L * 24 * 60 * 60

    fun build(
        airing: List<Anime>,
        favorites: Set<Int>,
        nowSec: Long = System.currentTimeMillis() / 1000,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AiringDay> {
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toEpochSecond()
        val end = start + WEEK_SEC
        val slots = airing.flatMap { anime ->
            var at = anime.nextAirAt ?: return@flatMap emptyList()
            var episode = anime.nextAirEp
            // Older than a month: the show has most likely ended or gone on break; do not guess.
            if (at < start - 4 * WEEK_SEC) return@flatMap emptyList()
            while (at < start) {
                at += WEEK_SEC
                episode = episode?.plus(1)
            }
            // Once today's episode is out, a refresh moves the recorded time to next week; the slot
            // a week earlier is that episode, so today keeps showing it as "Out".
            listOf(at to episode, at - WEEK_SEC to episode?.minus(1))
                .filter { (time, _) -> time in start until end && time <= maxOf(nowSec, at) }
                .map { (time, ep) -> AiringSlot(anime, time, ep, anime.id in favorites) }
        }
        val byDate = slots.groupBy { Instant.ofEpochSecond(it.airAtSec).atZone(zone).toLocalDate() }
        return (0 until 7).map { offset ->
            val date = today.plusDays(offset.toLong())
            AiringDay(
                date = date,
                dayOfWeek = date.dayOfWeek,
                slots = byDate[date].orEmpty().sortedWith(compareBy({ it.airAtSec }, { !it.favorite })),
            )
        }
    }
}
