package com.alirezaiyan.vokab.server.notification.rescue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RescuePlanValidatorTest {

    private val pool = RescuePool(
        sourceLanguage = "en",
        targetLanguage = "de",
        nearMastery = (1L..7L).map { candidateRow(it) },
        weak = listOf(candidateRow(20, level = 2), candidateRow(21, level = 2)),
    )

    @Test
    fun `accepts a well-formed session arc`() {
        val picks = listOf(1L, 2L, 20L, 3L, 21L, 4L, 5L, 6L)

        assertEquals(picks, RescuePlanValidator.validPicks(picks, pool))
    }

    @Test
    fun `rejects ids outside the pool`() {
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 2L, 20L, 3L, 4L, 999L, 5L), pool))
    }

    @Test
    fun `rejects duplicates`() {
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 1L, 20L, 3L, 4L, 5L), pool))
    }

    @Test
    fun `rejects too few or too many words`() {
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 20L, 2L, 3L, 4L), pool))
        assertNull(RescuePlanValidator.validPicks((1L..7L).toList() + listOf(20L, 21L) + (1L..2L), pool))
    }

    @Test
    fun `needs at least one weak word and four near mastery words`() {
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 2L, 3L, 4L, 5L, 6L), pool))          // no weak word
        val threeNear = RescuePool("en", "de", (1L..3L).map { candidateRow(it) }, (20L..22L).map { candidateRow(it, level = 2) })
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 20L, 21L, 22L, 2L, 3L), threeNear)) // only 3 near mastery
    }

    @Test
    fun `opens and closes on near mastery words`() {
        assertNull(RescuePlanValidator.validPicks(listOf(20L, 1L, 2L, 3L, 4L, 5L), pool))
        assertNull(RescuePlanValidator.validPicks(listOf(1L, 2L, 3L, 4L, 5L, 20L), pool))
    }
}
