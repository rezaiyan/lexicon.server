package com.alirezaiyan.vokab.server.notification.rescue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RescueFallbackTest {

    private val pool = RescuePool(
        sourceLanguage = "en",
        targetLanguage = "de",
        nearMastery = listOf(
            candidateRow(1, recent = "1111"), candidateRow(2, recent = "1110"), candidateRow(3, recent = "1111"),
            candidateRow(4, recent = "1100"), candidateRow(5, recent = "1111"), candidateRow(6, recent = "1111"),
            candidateRow(7, recent = "1111"),
        ),
        weak = listOf(
            candidateRow(20, text = "Gewitter", level = 2), candidateRow(21, level = 2), candidateRow(22, level = 2),
        ),
    )

    @Test
    fun `picks eight words as an arc with two weak words in the middle`() {
        val picks = RescueFallback.picks(pool)

        assertEquals(8, picks.size)
        assertEquals(listOf(20L, 21L), picks.subList(2, 4))
        assertTrue(picks.first() in pool.nearMastery.map { it.wordId })
        assertTrue(picks.last() in pool.nearMastery.map { it.wordId })
        assertNotNull(RescuePlanValidator.validPicks(picks, pool))
    }

    @Test
    fun `a minimal pool still makes a valid session`() {
        val minimal = pool.copy(nearMastery = pool.nearMastery.take(5), weak = pool.weak.take(1))

        val picks = RescueFallback.picks(minimal)

        assertEquals(6, picks.size)
        assertNotNull(RescuePlanValidator.validPicks(picks, minimal))
    }

    @Test
    fun `template copy names the first weak word and states the cost`() {
        val copy = RescueFallback.copy(pool, RescueFallback.picks(pool))

        assertEquals("rematch", copy.angle)
        assertEquals("Gewitter wants a rematch", copy.title)
        assertEquals("8 words, about 3 min. Most of them you already know.", copy.body)
        assertEquals("Gewitter: cracked. Nice comeback.", copy.outroWin)
    }

    @Test
    fun `long weak words get a generic title that fits`() {
        val long = pool.copy(weak = listOf(candidateRow(20, text = "Donaudampfschifffahrtsgesellschaft", level = 2)))

        val copy = RescueFallback.copy(long, RescueFallback.picks(long))

        assertTrue(RescueCopyLinter.graphemes(copy.title) <= RescueCopyLinter.TITLE_MAX)
    }
}
