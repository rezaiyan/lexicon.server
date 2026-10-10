package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WordRulesTest {

    @Test
    fun `slipping words fires for three words whose latest review this week was a demotion`() {
        val facts = (1L..3L).map { review("2026-06-16T09:00", wordId = it, correct = false, previousLevel = 3) }
        val card = SlippingWordsRule().evaluate(snapshot(reviews = facts))
        assertNotNull(card)
        assertEquals("3 words need a refresh", card!!.title)
        assertEquals(listOf(1L, 2L, 3L), card.action.wordIds)
        assertEquals(CoachActionKind.REVIEW_WORDS, card.action.kind)
    }

    @Test
    fun `slipping words ignores words that recovered later`() {
        val facts = (1L..3L).map { review("2026-06-15T09:00", wordId = it, correct = false, previousLevel = 3) } +
            review("2026-06-16T09:00", wordId = 1, correct = true, previousLevel = 2)
        assertNull(SlippingWordsRule().evaluate(snapshot(reviews = facts)))
    }

    @Test
    fun `slipping words ignores demotions older than seven days`() {
        val facts = (1L..3L).map { review("2026-06-09T09:00", wordId = it, correct = false, previousLevel = 3) }
        assertNull(SlippingWordsRule().evaluate(snapshot(reviews = facts)))
    }

    @Test
    fun `difficult words fires for three words under 50 percent`() {
        val words = listOf(
            WordAccuracy(1, "a", 30, 5), WordAccuracy(2, "b", 40, 4), WordAccuracy(3, "c", 49, 3), WordAccuracy(4, "d", 80, 9),
        )
        val card = DifficultWordsRule().evaluate(snapshot(difficultWords = words))
        assertNotNull(card)
        assertEquals(listOf(1L, 2L, 3L), card!!.action.wordIds)
    }

    @Test
    fun `difficult words silent with fewer than three`() {
        val words = listOf(WordAccuracy(1, "a", 30, 5), WordAccuracy(2, "b", 40, 4))
        assertNull(DifficultWordsRule().evaluate(snapshot(difficultWords = words)))
    }

    @Test
    fun `level bottleneck fires for a stage 15pt below the others`() {
        val facts = reviews("2026-06-16T09:00", 20, 18, firstWordId = 100, previousLevel = 1) +
            reviews("2026-06-16T10:00", 20, 18, firstWordId = 200, previousLevel = 2) +
            reviews("2026-06-16T11:00", 20, 10, firstWordId = 300, previousLevel = 3)
        val card = LevelBottleneckRule().evaluate(snapshot(reviews = facts))
        assertNotNull(card)
        assertEquals("Stage 3 words are sticking less", card!!.title)
        assertEquals(20, card.action.wordIds.size)
    }

    @Test
    fun `level bottleneck silent when stages are close`() {
        val facts = reviews("2026-06-16T09:00", 20, 18, firstWordId = 100, previousLevel = 1) +
            reviews("2026-06-16T10:00", 20, 17, firstWordId = 200, previousLevel = 2) +
            reviews("2026-06-16T11:00", 20, 16, firstWordId = 300, previousLevel = 3)
        assertNull(LevelBottleneckRule().evaluate(snapshot(reviews = facts)))
    }

    @Test
    fun `comeback win fires only for comebacks mastered this past week`() {
        val facts = listOf(review("2026-06-16T09:00", wordId = 7, correct = true, previousLevel = 5, newLevel = 6))
        val card = ComebackWinRule().evaluate(
            snapshot(reviews = facts, comebackWords = listOf(WordRef(7, "ubiquitous"), WordRef(8, "old")))
        )
        assertNotNull(card)
        assertEquals("1 comeback word", card!!.title)
        assertEquals(listOf(WordChipDto(7, "ubiquitous")), card.words)
        assertEquals(CoachActionKind.NONE, card.action.kind)
    }

    @Test
    fun `comeback win silent when no recent mastery`() {
        assertNull(ComebackWinRule().evaluate(snapshot(comebackWords = listOf(WordRef(8, "old")))))
    }
}
