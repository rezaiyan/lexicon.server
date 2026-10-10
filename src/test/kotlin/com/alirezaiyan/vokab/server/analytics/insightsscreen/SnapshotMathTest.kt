package com.alirezaiyan.vokab.server.analytics.insightsscreen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SnapshotMathTest {

    @Test
    fun `this week and last week are ISO weeks in the user's zone`() {
        val s = snapshot()
        assertEquals(Period(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21)), s.thisWeek())
        assertEquals(Period(LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 14)), s.lastWeek())
    }

    @Test
    fun `a review late Sunday UTC counts as Monday in Tokyo`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 2026-06-14T20:00Z = Mon 2026-06-15 05:00 in Tokyo
        val fact = review("2026-06-15T05:00", zone = tokyo)
        val s = snapshot(now = ZonedDateTime.of(2026, 6, 17, 12, 0, 0, 0, tokyo), reviews = listOf(fact))
        assertEquals(1, s.reviewsIn(s.thisWeek()).size)
        assertEquals(0, s.reviewsIn(s.lastWeek()).size)
    }

    @Test
    fun `accuracy is null below the sample gate and rounded above it`() {
        assertNull(reviews("2026-06-16T09:00", count = 19, correctCount = 19).gatedAccuracyPct())
        assertEquals(67, reviews("2026-06-16T09:00", count = 30, correctCount = 20).gatedAccuracyPct())
    }

    @Test
    fun `leveled up counts distinct words that moved up`() {
        val facts = listOf(
            review("2026-06-16T09:00", wordId = 1, correct = true),
            review("2026-06-16T10:00", wordId = 1, correct = true),
            review("2026-06-16T11:00", wordId = 2, correct = false),
        )
        assertEquals(1, facts.leveledUpWordCount())
    }

    @Test
    fun `reviews by date groups in local time`() {
        val s = snapshot(reviews = listOf(review("2026-06-16T23:30"), review("2026-06-17T00:30")))
        assertEquals(mapOf(LocalDate.of(2026, 6, 16) to 1, LocalDate.of(2026, 6, 17) to 1), s.reviewsByDate())
    }

    @Test
    fun `hour and weekday buckets use local time`() {
        val s = snapshot(reviews = listOf(review("2026-06-16T20:15")))
        assertEquals(setOf(20), s.hourBuckets().keys)
        assertEquals(setOf(DayOfWeek.TUESDAY), s.weekdayBuckets().keys)
    }

    @Test
    fun `next milestone uses fixed steps then every 500`() {
        assertEquals(50, nextMilestone(0))
        assertEquals(100, nextMilestone(50))
        assertEquals(1000, nextMilestone(999))
        assertEquals(1500, nextMilestone(1000))
        assertEquals(2000, nextMilestone(1700))
    }
}
