package com.alirezaiyan.vokab.server.notification.rescue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal fun candidateRow(
    id: Long,
    text: String = "w$id",
    level: Int = 4,
    recent: String = "1111",
    total: Int = 4,
    errors: Int = 0,
    nextReviewDate: Long = 0L,
    source: String = "en",
    target: String = "de",
) = RescueCandidateRow(
    wordId = id, text = text, translation = "t$id", sourceLanguage = source, targetLanguage = target,
    level = level, nextReviewDate = nextReviewDate, totalReviews = total, errors = errors, recent = recent,
)

class RescuePoolTest {

    private val now = 1_000_000_000L

    @Test
    fun `near mastery needs level 3 to 5, good recent accuracy and to be due soon`() {
        val rows = listOf(
            candidateRow(1, level = 5, recent = "1111"),
            candidateRow(2, level = 2, recent = "1111"),                       // level too low
            candidateRow(3, level = 4, recent = "1010"),                       // 50% recent
            candidateRow(4, level = 4, recent = "1"),                          // too little history
            candidateRow(5, level = 3, recent = "1110", nextReviewDate = now + RescuePool.DUE_SOON_MS + 1), // not due soon
            candidateRow(6, level = 3, recent = "1110", nextReviewDate = now + RescuePool.DUE_SOON_MS),
        )

        val pool = requireNotNull(RescuePool.from(rows, now))

        assertEquals(listOf(1L, 6L), pool.nearMastery.map { it.wordId })
    }

    @Test
    fun `weak words are missed often but have been answered right at least once`() {
        val rows = listOf(
            candidateRow(10, level = 2, recent = "0101", total = 6, errors = 3),
            candidateRow(11, level = 1, recent = "0000", total = 4, errors = 4),  // never right
            candidateRow(12, level = 2, recent = "01", total = 2, errors = 1),    // too few reviews
            candidateRow(13, level = 2, recent = "1111", total = 10, errors = 2), // error rate 20%
        )

        val pool = requireNotNull(RescuePool.from(rows, now))

        assertEquals(listOf(10L), pool.weak.map { it.wordId })
    }

    @Test
    fun `weak words closest to cracking come first`() {
        val rows = listOf(
            candidateRow(20, level = 2, recent = "0001", total = 8, errors = 6),
            candidateRow(21, level = 2, recent = "1101", total = 8, errors = 4),
        )

        val pool = requireNotNull(RescuePool.from(rows, now))

        assertEquals(listOf(21L, 20L), pool.weak.map { it.wordId })
    }

    @Test
    fun `pool keeps only the language pair with the most candidates`() {
        val rows = listOf(
            candidateRow(1, target = "de"), candidateRow(2, target = "de"),
            candidateRow(3, target = "fr"),
        )

        val pool = requireNotNull(RescuePool.from(rows, now))

        assertEquals("de", pool.targetLanguage)
        assertEquals(listOf(1L, 2L), pool.nearMastery.map { it.wordId })
    }

    @Test
    fun `buckets are capped`() {
        val rows = (1L..30L).map { candidateRow(it) } +
            (100L..120L).map { candidateRow(it, level = 2, recent = "0101", total = 6, errors = 3) }

        val pool = requireNotNull(RescuePool.from(rows, now))

        assertEquals(RescuePool.MAX_NEAR_MASTERY, pool.nearMastery.size)
        assertEquals(RescuePool.MAX_WEAK, pool.weak.size)
    }

    @Test
    fun `a pool can plan with five near mastery words and one weak word`() {
        val near = (1L..5L).map { candidateRow(it) }
        val weak = candidateRow(50, level = 2, recent = "0101", total = 6, errors = 3)

        assertTrue(requireNotNull(RescuePool.from(near + weak, now)).canPlan)
        assertFalse(requireNotNull(RescuePool.from(near.drop(1) + weak, now)).canPlan)
        assertFalse(requireNotNull(RescuePool.from(near, now)).canPlan)
    }

    @Test
    fun `no rows means no pool`() {
        assertNull(RescuePool.from(emptyList(), now))
    }
}
