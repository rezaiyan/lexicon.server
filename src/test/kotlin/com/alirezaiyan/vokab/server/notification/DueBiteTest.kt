package com.alirezaiyan.vokab.server.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DueBiteTest {

    @Test
    fun `picks at most a session of words however many are due`() {
        val due = (1L..154L).map { word(it, level = 3, nextReview = it) }

        val bite = bite(due)

        assertEquals(DueBite.SIZE, bite.wordIds.size)
        assertEquals(3, bite.minutes)
    }

    @Test
    fun `opens on easy wins and puts at most two hard words between them`() {
        val easy = (1L..10L).map { word(it, level = 4, nextReview = it) }
        val hard = (11L..20L).map { word(it, level = 1, nextReview = it) }

        val bite = bite(easy + hard)

        assertEquals(listOf(1L, 2L, 11L, 12L, 3L, 4L, 5L, 6L), bite.wordIds)
        assertTrue(bite.mostlyKnown)
    }

    @Test
    fun `most overdue easy words come first`() {
        val due = listOf(word(1, level = 5, nextReview = 300), word(2, level = 5, nextReview = 100))

        assertEquals(listOf(2L, 1L), bite(due).wordIds)
    }

    @Test
    fun `fills with hard words when there are not enough easy ones`() {
        val due = listOf(word(1, level = 3, nextReview = 1)) + (2L..12L).map { word(it, level = 0, nextReview = it) }

        val bite = bite(due)

        assertEquals(DueBite.SIZE, bite.wordIds.size)
        assertEquals(1L, bite.wordIds.first())
        assertEquals(false, bite.mostlyKnown)
    }

    @Test
    fun `a few due words make a one minute bite`() {
        val bite = bite(listOf(word(1, level = 2, nextReview = 1), word(2, level = 4, nextReview = 2)))

        assertEquals(listOf(2L, 1L), bite.wordIds)
        assertEquals(1, bite.minutes)
    }

    @Test
    fun `nothing due makes no bite`() {
        assertEquals(null, DueBite.pick(emptyList()))
    }

    private fun bite(due: List<DueWordRow>) = requireNotNull(DueBite.pick(due))

    private fun word(id: Long, level: Int, nextReview: Long) = DueWordRow(id, level, nextReview)
}
