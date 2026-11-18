package com.animo.app.domain

import com.animo.app.domain.model.AiringSchedule
import com.animo.app.domain.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test
    fun `an episode out earlier today stays on today after the time moves to next week`() {
        val week = AiringSchedule.build(listOf(anime(1, now - 3600 + 7 * day, ep = 8)), emptySet(), now, zone)
        val slot = week[0].slots.single()
        assertEquals(7, slot.episode)
        assertEquals(1, week.sumOf { it.slots.size })
    }
}
