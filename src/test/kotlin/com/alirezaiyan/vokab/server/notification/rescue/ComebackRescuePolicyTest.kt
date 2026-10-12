package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_TODAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComebackRescuePolicyTest {

    private fun daysAgo(vararg days: Long) = days.map { TEST_TODAY.minusDays(it) }

    private fun signals(
        studyDates: List<java.time.LocalDate> = daysAgo(3, 4, 5),
        totalWords: Int = 40,
        alreadyRescued: Boolean = false,
    ) = LapseSignals(studyDates, totalWords, alreadyRescued)

    @Test
    fun `should rescue a learner with a habit who missed two full days`() {
        assertTrue(ComebackRescuePolicy.isEligible(signals(), TEST_TODAY))
    }

    @Test
    fun `should not rescue after only one missed day`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(studyDates = daysAgo(2, 3, 4)), TEST_TODAY))
    }

    @Test
    fun `should leave lapses longer than two weeks to the existing flow`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(studyDates = daysAgo(15, 16, 17)), TEST_TODAY))
        assertTrue(ComebackRescuePolicy.isEligible(signals(studyDates = daysAgo(14, 15, 16)), TEST_TODAY))
    }

    @Test
    fun `should require three study days in the two weeks before the lapse`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(studyDates = daysAgo(3, 4)), TEST_TODAY))
        // Day 17 is outside the 14 days ending on day 3
        assertFalse(ComebackRescuePolicy.isEligible(signals(studyDates = daysAgo(3, 4, 17)), TEST_TODAY))
    }

    @Test
    fun `should require enough words for a varied session`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(totalWords = ComebackRescuePolicy.MIN_WORDS - 1), TEST_TODAY))
    }

    @Test
    fun `should rescue once per lapse`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(alreadyRescued = true), TEST_TODAY))
    }

    @Test
    fun `should not rescue someone who never studied`() {
        assertFalse(ComebackRescuePolicy.isEligible(signals(studyDates = emptyList()), TEST_TODAY))
    }

    @Test
    fun `daysAway counts from the last study day`() {
        assertEquals(3L, ComebackRescuePolicy.daysAway(daysAgo(5, 3, 4), TEST_TODAY))
        assertNull(ComebackRescuePolicy.daysAway(emptyList(), TEST_TODAY))
    }
}
