package com.animo.app.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
 * A week here is seven *dates*, not 604800 seconds: a clock change makes those differ by an hour,
 * and adding seconds to a weekly slot would slide a late-night show onto the next day.
 *
 * Pure: the clock and zone are arguments, so the projection is unit-testable.
 */
object AiringSchedule {

    /** Older than this: the show has most likely ended or gone on break, so do not guess. */
    private const val STALE_DAYS = 28L

    fun build(
        airing: List<Anime>,
        favorites: Set<Int>,
        nowSec: Long = System.currentTimeMillis() / 1000,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AiringDay> {
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toEpochSecond()
        // Seven dates, so the window ends at the next midnight even when a DST change lands inside it.
        val end = today.plusDays(7).atStartOfDay(zone).toEpochSecond()
        val slots = airing.flatMap { anime ->
            val recorded = anime.nextAirAt ?: return@flatMap emptyList()
            var at = Instant.ofEpochSecond(recorded).atZone(zone)
            var episode = anime.nextAirEp
            if (ChronoUnit.DAYS.between(at.toLocalDate(), today) > STALE_DAYS) return@flatMap emptyList()
            // A weekly show keeps its weekday and its wall-clock time, so project by date, not seconds.
            while (at.toLocalDate() < today) {
                at = at.plusWeeks(1)
                episode = episode?.plus(1)
            }
            val atSec = at.toEpochSecond()
            // Once today's episode is out, a refresh moves the recorded time to next week; the slot
            // a week earlier is that episode, so today keeps showing it as "Out".
            listOf(atSec to episode, at.minusWeeks(1).toEpochSecond() to episode?.minus(1))
                .filter { (time, _) -> time in start until end && time <= maxOf(nowSec, atSec) }
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
