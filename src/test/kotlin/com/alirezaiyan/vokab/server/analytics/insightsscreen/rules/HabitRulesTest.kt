package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.CoachActionKind
import com.alirezaiyan.vokab.server.analytics.insightsscreen.TEST_LOCAL_NOW
import com.alirezaiyan.vokab.server.analytics.insightsscreen.review
import com.alirezaiyan.vokab.server.analytics.insightsscreen.reviews
import com.alirezaiyan.vokab.server.analytics.insightsscreen.reviewsAt
import com.alirezaiyan.vokab.server.analytics.insightsscreen.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HabitRulesTest {

    private val streak = StreakAtRiskRule()
    private val bestTime = BestTimeRule()

    // TEST_LOCAL_NOW = Wed 2026-06-17 20:00 Berlin

    @Test
    fun `streak at risk fires in the evening when yesterday studied and today not`() {
        val card = streak.evaluate(snapshot(currentStreak = 6, reviews = listOf(review("2026-06-16T09:00"))))
        assertNotNull(card)
        assertEquals("Keep your 6-day streak alive", card!!.title)
        assertEquals(CoachActionKind.START_REVIEW, card.action.kind)
    }

    @Test
    fun `streak at risk is silent before 18h`() {
        val morning = TEST_LOCAL_NOW.withHour(10)
        assertNull(streak.evaluate(snapshot(now = morning, currentStreak = 6, reviews = listOf(review("2026-06-16T09:00")))))
    }

    @Test
    fun `streak at risk is silent when already studied today`() {
        val facts = listOf(review("2026-06-16T09:00"), review("2026-06-17T08:00"))
        assertNull(streak.evaluate(snapshot(currentStreak = 6, reviews = facts)))
    }

    @Test
    fun `streak at risk is silent without a streak`() {
        assertNull(streak.evaluate(snapshot(currentStreak = 0, reviews = listOf(review("2026-06-16T09:00")))))
    }

    @Test
    fun `best time fires for an hour 10pt above overall with reminders off`() {
        val facts = reviews("2026-06-10T20:00", count = 30, correctCount = 28) +       // 93% at 20h
            reviews("2026-06-11T09:00", count = 30, correctCount = 18, firstWordId = 500) // 60% at 9h
        val card = bestTime.evaluate(snapshot(reviews = facts, remindersEnabled = false))
        assertNotNull(card)
        assertEquals(20, card!!.action.hour)
        assertEquals(CoachActionKind.ENABLE_REMINDER, card.action.kind)
    }

    @Test
    fun `best time is silent when reminders already on`() {
        val facts = reviews("2026-06-10T20:00", count = 30, correctCount = 28) +
            reviews("2026-06-11T09:00", count = 30, correctCount = 18, firstWordId = 500)
        assertNull(bestTime.evaluate(snapshot(reviews = facts, remindersEnabled = true)))
    }

    @Test
    fun `best time ignores hours below the bucket gate`() {
        val facts = reviews("2026-06-10T20:00", count = 29, correctCount = 29) +
            reviews("2026-06-11T09:00", count = 40, correctCount = 24, firstWordId = 500)
        assertNull(bestTime.evaluate(snapshot(reviews = facts)))
    }

    @Test
    fun `best hour follows the requested zone for the same instants`() {
        val morningUtc = java.time.Instant.parse("2026-06-10T00:00:00Z") // Tokyo 09h, Los Angeles 17h
        val eveningUtc = java.time.Instant.parse("2026-06-10T12:00:00Z") // Tokyo 21h, Los Angeles 05h
        fun bestHourIn(zone: java.time.ZoneId): Int? {
            val facts = reviewsAt(morningUtc, zone, count = 30, correctCount = 28) +
                reviewsAt(eveningUtc, zone, count = 30, correctCount = 18, firstWordId = 500)
            val now = TEST_LOCAL_NOW.withZoneSameInstant(zone)
            return bestTime.evaluate(snapshot(now = now, reviews = facts))?.action?.hour
        }
        assertEquals(9, bestHourIn(java.time.ZoneId.of("Asia/Tokyo")))
        assertEquals(17, bestHourIn(java.time.ZoneId.of("America/Los_Angeles")))
    }
}
