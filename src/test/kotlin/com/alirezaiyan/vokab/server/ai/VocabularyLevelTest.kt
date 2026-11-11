package com.alirezaiyan.vokab.server.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VocabularyLevelTest {

    @Test
    fun `fromLabel reads the client level keys in any case`() {
        assertEquals(VocabularyLevel.BEGINNER, VocabularyLevel.fromLabel("beginner"))
        assertEquals(VocabularyLevel.INTERMEDIATE, VocabularyLevel.fromLabel(" Intermediate "))
        assertEquals(VocabularyLevel.ADVANCED, VocabularyLevel.fromLabel("ADVANCED"))
    }

    @Test
    fun `fromLabel reads CEFR codes`() {
        assertEquals(VocabularyLevel.BEGINNER, VocabularyLevel.fromLabel("A2"))
        assertEquals(VocabularyLevel.INTERMEDIATE, VocabularyLevel.fromLabel("b1"))
        assertEquals(VocabularyLevel.ADVANCED, VocabularyLevel.fromLabel("C2"))
    }

    @Test
    fun `fromLabel returns null for an unknown level`() {
        assertNull(VocabularyLevel.fromLabel("expert-ish"))
        assertNull(VocabularyLevel.fromLabel(""))
    }
}
