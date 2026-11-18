package com.animo.app.domain

import com.animo.app.domain.model.AiringSchedule
import com.animo.app.domain.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class AiringScheduleTest {

    private val zone = ZoneOffset.UTC
    // 2026-11-18 09:00 UTC (a Wednesday)
    private val now = 1_794_992_400L
    private val day = 86_400L

    private fun anime(id: Int, at: Long?, ep: Int? = 5) = Anime(id = id, title = "A$id", nextAirAt = at, nextAirEp = ep)

    @Test
    fun `seven days starting today`() {
        val week = AiringSchedule.build(emptyList(), emptySet(), now, zone)
        assertEquals(7, week.size)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, week[0].dayOfWeek)
    }

    @Test
    fun `future time lands on its day`() {
        val week = AiringSchedule.build(listOf(anime(1, now + 2 * day)), emptySet(), now, zone)
        assertEquals(1, week[2].slots.size)
        assertEquals(5, week[2].slots[0].episode)
    }

    @Test
    fun `passed time moves forward a week and bumps the episode`() {
        val week = AiringSchedule.build(listOf(anime(1, now - 2 * day)), emptySet(), now, zone)
        val slot = week[5].slots.single()
        assertEquals(now + 5 * day, slot.airAtSec)
        assertEquals(6, slot.episode)
    }

    @Test
    fun `earlier today stays today`() {
        val week = AiringSchedule.build(listOf(anime(1, now - 3600)), setOf(1), now, zone)
        assertTrue(week[0].slots.single().favorite)
    }

    @Test
    fun `stale and unknown times are dropped`() {
        val week = AiringSchedule.build(listOf(anime(1, now - 60 * day), anime(2, null)), emptySet(), now, zone)
        assertTrue(week.all { it.slots.isEmpty() })
    }

    /**
     * A weekly slot keeps its weekday across a clock change. Adding 604800 seconds instead slid a
     * Sunday 23:30 show to Monday 00:30 the week the clocks went forward.
     */
    @Test
    fun `a weekly slot keeps its weekday across spring forward`() {
        val newYork = ZoneId.of("America/New_York")
        // Recorded Sun 2026-03-01 23:30 EST; asked on Wed 2026-03-04 09:00 EST.
        val week = AiringSchedule.build(
            listOf(anime(1, 1_772_425_800L)),
            emptySet(),
            1_772_632_800L,
            newYork,
        )
        val slot = week[4].slots.single()
        assertEquals(java.time.DayOfWeek.SUNDAY, week[4].dayOfWeek)
        assertEquals(1_773_027_000L, slot.airAtSec) // Sun 2026-03-08 23:30 EDT
    }

    /** The window is seven dates, so the last hour of day 7 survives a fall-back. */
    @Test
    fun `a slot late on day seven survives the end of daylight saving`() {
        val newYork = ZoneId.of("America/New_York")
        // Asked Thu 2026-10-29 09:00 EDT; the clocks go back on Sun 2026-11-01.
        val week = AiringSchedule.build(
            listOf(anime(1, 1_793_853_000L)),
            emptySet(),
            1_793_278_800L,
            newYork,
        )
        assertEquals(1_793_853_000L, week[6].slots.single().airAtSec) // Wed 2026-11-04 23:30 EST
    }

    @Test
    fun `an episode out earlier today stays on today after the time moves to next week`() {
        val week = AiringSchedule.build(listOf(anime(1, now - 3600 + 7 * day, ep = 8)), emptySet(), now, zone)
        val slot = week[0].slots.single()
        assertEquals(7, slot.episode)
        assertEquals(1, week.sumOf { it.slots.size })
    }
}
