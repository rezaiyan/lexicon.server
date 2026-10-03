package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.config.VocabularyConfig
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.SuggestVocabularyItemResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VocabularySuggestionServiceTest {

    private val openRouterService = mockk<OpenRouterService>()
    private val wordService = mockk<WordService>()
    private val appProperties = AppProperties(vocabulary = VocabularyConfig(suggestionCount = 3))
    private val service = VocabularySuggestionService(openRouterService, wordService, appProperties)

    private val user = User(id = 1L, email = "u@example.com", name = "U")

    private fun item(word: String) = SuggestVocabularyItemResponse(originalWord = word, translation = "t-$word")

    private fun aiReturns(vararg words: String) {
        every {
            openRouterService.generateVocabularyFromPreferences(any(), any(), any(), any())
        } returns words.map(::item)
    }

    @Test
    fun `suggestForOnboarding drops blank and case-insensitive duplicate words`() {
        aiReturns("Haus", " ", "haus ", "Baum", "Auto")

        val response = service.suggestForOnboarding("German", "beginner", "English", emptyList())

        assertEquals(listOf("Haus", "Baum", "Auto"), response.items.map { it.originalWord })
    }

    @Test
    fun `suggestForOnboarding caps items at the configured suggestion count`() {
        aiReturns("a", "b", "c", "d", "e")

        val response = service.suggestForOnboarding("German", "beginner", "English", emptyList())

        assertEquals(3, response.items.size)
    }

    @Test
    fun `suggestForOnboarding trims request fields and blank interests before calling the AI`() {
        every {
            openRouterService.generateVocabularyFromPreferences("German", "beginner", "English", listOf("travel"))
        } returns listOf(item("Reise"))

        val response = service.suggestForOnboarding(" German ", " beginner", "English ", listOf(" travel ", "  "))

        assertEquals("German", response.targetLanguage)
        assertEquals("beginner", response.currentLevel)
        assertEquals("English", response.nativeLanguage)
        assertEquals(listOf("Reise"), response.items.map { it.originalWord })
    }

    @Test
    fun `suggestForUser drops words the user already has`() {
        aiReturns("Haus", "Baum", "Auto")
        every { wordService.getExistingTranslationKeys(user.requireId(), "German") } returns setOf("haus")

        val response = service.suggestForUser(user.requireId(), " German", "beginner", "English")

        assertEquals(listOf("Baum", "Auto"), response.items.map { it.originalWord })
    }
}
