package com.alirezaiyan.vokab.server.notification.rescue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RescueFactSheetBuilderTest {

    private val pool = RescuePool(
        sourceLanguage = "en",
        targetLanguage = "de",
        nearMastery = listOf(
            candidateRow(1, text = "Schmetterling", level = 5),
            candidateRow(2, text = "Apfel", level = 5),
            candidateRow(3, text = "Hund", level = 4),
        ),
        weak = listOf(candidateRow(10, text = "Gewitter", level = 2, recent = "10100", total = 9, errors = 5)),
    )

    private fun profile(longestStreak: Int = 19, firstWord: FirstWord? = null) =
        RescueProfile(daysAway = 3, totalWords = 212, longestStreak = longestStreak, firstWord = firstWord)

    @Test
    fun `facts are numbered in order and always start with the session size`() {
        val sheet = RescueFactSheetBuilder.build(profile(), pool)

        assertEquals("F1", sheet.facts.first().id)
        assertEquals("session: 8 words, about 3 minutes", sheet.facts.first().text)
        assertEquals(sheet.facts.indices.map { "F${it + 1}" }, sheet.facts.map { it.id })
    }

    @Test
    fun `weak word fact states its real recent record`() {
        val sheet = RescueFactSheetBuilder.build(profile(), pool)

        val weak = sheet.facts.single { "Gewitter" in it.words }
        assertEquals("weak word \"Gewitter\" (t10): missed 3 of last 5, right on the last try", weak.text)
    }

    @Test
    fun `level 5 words are one review from mastered`() {
        val sheet = RescueFactSheetBuilder.build(profile(), pool)

        assertTrue(sheet.facts.any { it.text == "words one review from mastered: 2" })
        assertTrue(sheet.facts.any { "Schmetterling" in it.words })
    }

    @Test
    fun `short streaks are not worth mentioning`() {
        val sheet = RescueFactSheetBuilder.build(profile(longestStreak = 2), pool)

        assertFalse(sheet.facts.any { it.text.startsWith("longest streak") })
    }

    @Test
    fun `first word is a fact only when it can be in the session`() {
        val inPool = FirstWord("Apfel", "apple", Instant.EPOCH)
        val notInPool = FirstWord("Brot", "bread", Instant.EPOCH)

        assertTrue(RescueFactSheetBuilder.build(profile(firstWord = inPool), pool).facts.any { it.text.startsWith("first word") })
        assertFalse(RescueFactSheetBuilder.build(profile(firstWord = notInPool), pool).facts.any { it.text.startsWith("first word") })
    }

    @Test
    fun `render lists one fact per line`() {
        val sheet = RescueFactSheet(listOf(RescueFact("F1", "a"), RescueFact("F2", "b")))

        assertEquals("F1 a\nF2 b", sheet.render())
    }
}
