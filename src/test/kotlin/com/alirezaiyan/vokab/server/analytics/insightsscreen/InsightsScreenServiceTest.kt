package com.alirezaiyan.vokab.server.analytics.insightsscreen

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InsightsScreenServiceTest {

    private val loader: LearnerSnapshotLoader = mockk()
    private val service = InsightsScreenService(loader, CoachEngine(listOf(WeekTrendRuleStub())))

    private class WeekTrendRuleStub : CoachRule {
        override val type = "WEEK_TREND"
        override val priority = 10
        override fun evaluate(snapshot: LearnerSnapshot) = card(snapshot, "t", "b", CoachActionDto(CoachActionKind.NONE))
    }

    @Test
    fun `assembles hero, coach, sections and locked from one snapshot`() {
        val s = snapshot(reviews = reviews("2026-06-16T09:00", 10, 8), totalReviewsAllTime = 140)
        every { loader.load(1L, BERLIN) } returns s

        val response = service.build(1L, BERLIN)

        assertEquals(140, response.totalReviews)
        assertEquals(10, response.hero.reviews.value)
        assertEquals(listOf("WEEK_TREND"), response.coach.map { it.type })
        assertEquals(listOf(LockedSectionDto(SectionKey.HABITS, 20)), response.locked)
        assertEquals(TEST_LOCAL_NOW.toInstant().toString(), response.generatedAt)
    }
}
