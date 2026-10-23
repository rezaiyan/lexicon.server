package com.alirezaiyan.vokab.server.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PromptTemplatesTest {

    private val templates = PromptTemplates()

    @Test
    fun `inline placeholders are replaced and trailing whitespace trimmed`() {
        val prompt = templates.render(Prompt.TRANSLATE, mapOf("targetLanguage" to "German", "text" to "good morning"))

        assertTrue(prompt.startsWith("Translate the following text to German."))
        assertTrue(prompt.endsWith("Just the translation:\n\ngood morning"))
    }

    @Test
    fun `values are not re-scanned, so user text cannot inject a placeholder`() {
        val prompt = templates.render(Prompt.TRANSLATE, mapOf("targetLanguage" to "German", "text" to "{{targetLanguage}}"))

        assertTrue(prompt.endsWith("\n\n{{targetLanguage}}"))
    }

    @Test
    fun `an empty block line is dropped and a filled one may span lines`() {
        val base = mapOf("userName" to "Ada", "currentStreak" to 5, "dueCardsRequirement" to "")

        val without = templates.render(Prompt.STREAK_REMINDER, base + ("progressLines" to ""))
        val with = templates.render(Prompt.STREAK_REMINDER, base + ("progressLines" to "- line one\n- line two"))

        assertTrue(without.contains("- Current streak: 5 days\n\nMessage Requirements:"))
        assertTrue(with.contains("- Current streak: 5 days\n- line one\n- line two\n\nMessage Requirements:"))
        assertFalse(without.contains("{{"))
    }

    @Test
    fun `a missing or unused value is rejected`() {
        assertThrows<IllegalArgumentException> { templates.render(Prompt.TRANSLATE, mapOf("targetLanguage" to "German")) }
        assertThrows<IllegalArgumentException> {
            templates.render(Prompt.TRANSLATE, mapOf("targetLanguage" to "German", "text" to "x", "typo" to "y"))
        }
    }

    @Test
    fun `every prompt has a template file on the classpath`() {
        Prompt.entries.forEach { prompt ->
            assertEquals(
                true,
                javaClass.getResource("/prompts/${prompt.resource}.txt") != null,
                "missing /prompts/${prompt.resource}.txt",
            )
        }
    }
}
