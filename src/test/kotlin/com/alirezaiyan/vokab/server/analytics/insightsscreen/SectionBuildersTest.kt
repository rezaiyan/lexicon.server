package com.alirezaiyan.vokab.server.analytics.insightsscreen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SectionBuildersTest {

    @Test
    fun `habits is locked with reviews needed below the gate`() {
        val result = SectionBuilders.build(snapshot(reviews = reviews("2026-06-16T09:00", 12, 10)))
        assertNull(result.sections.habits)
        assertEquals(listOf(LockedSectionDto(SectionKey.HABITS, 18)), result.locked)
    }

    @Test
    fun `habits unlocked shows best weekday caption`() {
        val facts = reviews("2026-06-16T09:00", 30, 27) + reviews("2026-06-11T09:00", 30, 15, firstWordId = 500)
        val habits = SectionBuilders.build(snapshot(reviews = facts)).sections.habits
        assertNotNull(habits)
        assertEquals("You're sharpest on Tuesdays", habits!!.caption)
        assertEquals(BestHourDto(9, 70), habits.bestHour)
        assertEquals(2, habits.heatmap.size)
    }

    @Test
    fun `mastery lists all seven stages and weekly movement`() {
        val facts = listOf(review("2026-06-16T09:00", wordId = 1, correct = true))
        val mastery = SectionBuilders.build(snapshot(reviews = facts, wordsPerLevel = mapOf(0 to 4L, 6 to 2L))).sections.mastery
        assertNotNull(mastery)
        assertEquals((0..6).toList(), mastery!!.levels.map { it.level })
        assertEquals(1, mastery.promotedThisWeek)
        assertEquals("1 word moved up a stage this week", mastery.caption)
    }

    @Test
    fun `empty sections are hidden, not locked`() {
        val result = SectionBuilders.build(snapshot(reviews = reviews("2026-06-16T09:00", 40, 30)))
        assertNull(result.sections.mastery)
        assertNull(result.sections.words)
        assertNull(result.sections.wordRush)
        assertTrue(result.locked.isEmpty())
    }

    @Test
    fun `word rush section shows recent scores`() {
        val wr = SectionBuilders.build(snapshot(wordRush = WordRushSummary(14, 2300, listOf(1800, 2100)))).sections.wordRush
        assertEquals(WordRushSectionDto("Best score 2300", 14, 2300, listOf(1800, 2100)), wr)
    }
}
