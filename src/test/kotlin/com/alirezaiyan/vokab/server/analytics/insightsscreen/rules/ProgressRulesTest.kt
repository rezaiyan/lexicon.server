package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProgressRulesTest {

    @Test
    fun `milestone near fires within ten words`() {
        val card = MilestoneNearRule().evaluate(snapshot(masteredTotal = 93))
        assertNotNull(card)
        assertEquals("7 words from 100 mastered", card!!.title)
    }

    @Test
    fun `milestone near silent when far`() {
        assertNull(MilestoneNearRule().evaluate(snapshot(masteredTotal = 60)))
    }

    @Test
    fun `milestone near silent for brand new users`() {
        assertNull(MilestoneNearRule().evaluate(snapshot(masteredTotal = 0)))
    }

    @Test
    fun `week trend always fires`() {
        val empty = WeekTrendRule().evaluate(snapshot())
        assertEquals("Start your week", empty?.title)

        val ahead = WeekTrendRule().evaluate(
            snapshot(reviews = reviews("2026-06-16T09:00", 12, 10) + reviews("2026-06-09T09:00", 5, 5, firstWordId = 500))
        )
        assertEquals("12 reviews this week", ahead?.title)
        assertEquals("That's 7 more than last week. Nice momentum.", ahead?.body)

        val behind = WeekTrendRule().evaluate(
            snapshot(reviews = reviews("2026-06-16T09:00", 3, 3) + reviews("2026-06-09T09:00", 10, 10, firstWordId = 500))
        )
        assertEquals("7 more to match last week", behind?.body)
    }
}
