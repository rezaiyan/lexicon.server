package com.alirezaiyan.vokab.server.analytics.insightsscreen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoachEngineTest {

    private class FixedRule(override val type: String, override val priority: Int, private val fires: Boolean = true) : CoachRule {
        override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? =
            if (fires) card(snapshot, title = type, body = "", action = CoachActionDto(CoachActionKind.NONE)) else null
    }

    private class ThrowingRule : CoachRule {
        override val type = "BOOM"
        override val priority = 1000
        override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? = error("broken rule")
    }

    private class ErrorRule : CoachRule {
        override val type = "FATAL"
        override val priority = 1000
        override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? = throw NotImplementedError("vm-level error")
    }

    @Test
    fun `an Error from a rule propagates`() {
        val engine = CoachEngine(listOf(ErrorRule(), FixedRule("OK", 1)))
        assertThrows(NotImplementedError::class.java) { engine.cardsFor(snapshot()) }
    }

    @Test
    fun `returns at most three cards ordered by priority`() {
        val engine = CoachEngine(listOf(FixedRule("LOW", 10), FixedRule("TOP", 100), FixedRule("MID", 50), FixedRule("MID2", 40)))
        assertEquals(listOf("TOP", "MID", "MID2"), engine.cardsFor(snapshot()).map { it.type })
    }

    @Test
    fun `skips rules that do not fire`() {
        val engine = CoachEngine(listOf(FixedRule("A", 10, fires = false), FixedRule("B", 5)))
        assertEquals(listOf("B"), engine.cardsFor(snapshot()).map { it.type })
    }

    @Test
    fun `a throwing rule is skipped, not fatal`() {
        val engine = CoachEngine(listOf(ThrowingRule(), FixedRule("OK", 1)))
        assertEquals(listOf("OK"), engine.cardsFor(snapshot()).map { it.type })
    }

    @Test
    fun `card id is type plus local date and words are capped at five`() {
        val rule = FixedRule("X", 1)
        val words = (1L..8L).map { WordRef(it, "w$it") }
        val card = rule.card(snapshot(), "t", "b", CoachActionDto(CoachActionKind.NONE), words)
        assertEquals("X:2026-06-17", card.id)
        assertEquals(5, card.words.size)
        assertEquals(3, card.moreWordsCount)
    }
}
