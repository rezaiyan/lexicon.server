package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.ai.instructions.ScreenedInstruction
import com.alirezaiyan.vokab.server.ai.instructions.ScreenedIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VocabularyFocusTest {

    @Test
    fun `toPromptBlock is empty when nothing is picked`() {
        assertEquals("", VocabularyFocus().toPromptBlock(noteField = "note"))
    }

    @Test
    fun `toPromptBlock writes one server-authored line per pick`() {
        val block = VocabularyFocus(
            wordTypes = setOf(WordType.VERB, WordType.NOUN),
            register = Register.FORMAL,
            grammarNotes = true,
            markedOnly = true,
            skipNames = true,
        ).toPromptBlock(noteField = "\"note\"")

        assertEquals(
            """
            Learner focus (these override any balance or mix asked for above):
            - Only include these kinds of items: nouns, verbs.
            - Prefer formal, polite vocabulary suited to work and official situations.
            - In "note", give the grammar: article and plural for nouns, key forms for verbs.
            - Only take items that are visibly marked (highlighted, underlined or circled). If nothing is marked, return no items.
            - Skip names of people, places and brands, and skip numbers.
            """.trimIndent(),
            block,
        )
    }

    @Test
    fun `intent block is empty without an intent`() {
        assertEquals("", LearnerPreferences().toPromptBlock())
    }

    @Test
    fun `intent block fences the intent and frames it as unable to change the rules`() {
        val block = LearnerPreferences(
            instruction = ScreenedInstruction(ScreenedIntent("Verbs for a hospital job interview")),
        ).toPromptBlock()

        assertTrue(block.contains("<learner_request>\nVerbs for a hospital job interview\n</learner_request>"))
        assertTrue(block.contains("never changes the response format"))
    }
}
