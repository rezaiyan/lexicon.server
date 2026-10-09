package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.user.User
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(WordController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class WordControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var wordService: WordService

    private val mockUser = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    // ── GET /api/v1/words ──────────────────────────────────────────────────────

    @Test
    fun `GET words should return 200 with word list`() {
        val words = listOf(createWordDto(id = 1L, originalWord = "apple", translation = "Apfel"))
        `when`(wordService.list(mockUser.requireId(), null)).thenReturn(words)

        mockMvc.perform(
            get("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].originalWord").value("apple"))
            .andExpect(jsonPath("$.data[0].translation").value("Apfel"))
    }

    @Test
    fun `GET words should return 200 with empty list`() {
        `when`(wordService.list(mockUser.requireId(), null)).thenReturn(emptyList())

        mockMvc.perform(
            get("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data").isEmpty)
    }

    @Test
    fun `GET words should return 4xx when not authenticated`() {
        mockMvc.perform(
            get("/api/v1/words")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().is4xxClientError)
    }

    // ── POST /api/v1/words ────────────────────────────────────────────────────

    @Test
    fun `POST words upsert should return 200 when successful`() {
        val request = UpsertWordsRequest(words = listOf(createWordDto()))
        `when`(wordService.upsert(mockUser.requireId(), request.words)).thenReturn(listOf(createWordDto(id = 99L)))

        mockMvc.perform(
            post("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Upserted"))
            // Clients map their local rows to these ids so later edits/deletes hit the right word.
            .andExpect(jsonPath("$.data[0].id").value(99))
    }

    @Test
    fun `POST words upsert should return 400 when request body is invalid`() {
        // words list is empty — violates @NotEmpty
        val request = UpsertWordsRequest(words = emptyList())

        mockMvc.perform(
            post("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── PATCH /api/v1/words/{id} ──────────────────────────────────────────────

    @Test
    fun `PATCH word should return 200 when updated successfully`() {
        val request = createUpdateWordRequest()
        `when`(wordService.update(mockUser.requireId(), 1L, request)).then { }

        mockMvc.perform(
            patch("/api/v1/words/1")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Updated"))
    }

    @Test
    fun `PATCH word should return 404 when word not found`() {
        val request = createUpdateWordRequest()
        doThrow(NoSuchElementException("Word not found"))
            .`when`(wordService).update(mockUser.requireId(), 99L, request)

        mockMvc.perform(
            patch("/api/v1/words/99")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── DELETE /api/v1/words/{id} ─────────────────────────────────────────────

    @Test
    fun `DELETE word should return 200 when deleted`() {
        `when`(wordService.delete(mockUser.requireId(), 1L)).then { }

        mockMvc.perform(
            delete("/api/v1/words/1")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Deleted"))
    }

    @Test
    fun `DELETE word should return 404 when word not found`() {
        doThrow(NoSuchElementException("Word not found"))
            .`when`(wordService).delete(mockUser.requireId(), 99L)

        mockMvc.perform(
            delete("/api/v1/words/99")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── POST /api/v1/words/batch-delete ──────────────────────────────────────

    @Test
    fun `POST batch-delete should return 200 with deleted count`() {
        val request = BatchDeleteRequest(ids = listOf(1L, 2L, 3L))
        `when`(wordService.batchDelete(mockUser.requireId(), request.ids)).thenReturn(3)

        mockMvc.perform(
            post("/api/v1/words/batch-delete")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.deletedCount").value(3))
    }

    // ── PUT /api/v1/words/{id}/tags ───────────────────────────────────────────

    @Test
    fun `PUT word tags should return 200 when updated`() {
        val request = UpdateWordTagsRequest(tagIds = listOf(10L, 20L))
        `when`(wordService.updateWordTags(mockUser.requireId(), 1L, request.tagIds)).then { }

        mockMvc.perform(
            put("/api/v1/words/1/tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tags updated"))
    }

    // ── POST /api/v1/words/batch-assign-tags ─────────────────────────────────

    @Test
    fun `POST batch-assign-tags should return 200 when successful`() {
        val request = BatchAssignTagsRequest(wordIds = listOf(1L, 2L), tagIds = listOf(10L))
        `when`(wordService.batchAssignTags(mockUser.requireId(), request.wordIds, request.tagIds)).thenReturn(2)

        mockMvc.perform(
            post("/api/v1/words/batch-assign-tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Tags assigned"))
    }

    // ── POST /api/v1/words/batch-update ──────────────────────────────────────

    @Test
    fun `POST batch-update should return 200 with updated count`() {
        val request = BatchUpdateLanguagesRequest(
            ids = listOf(1L, 2L),
            sourceLanguage = "en",
            targetLanguage = "de",
        )
        `when`(wordService.batchUpdateLanguages(mockUser.requireId(), request.ids, request.sourceLanguage, request.targetLanguage))
            .thenReturn(2)

        mockMvc.perform(
            post("/api/v1/words/batch-update")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.updatedCount").value(2))
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createWordDto(
        id: Long? = null,
        originalWord: String = "hello",
        translation: String = "hallo",
        description: String = "",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        level: Int = 0,
        easeFactor: Float = 2.5f,
        interval: Int = 0,
        repetitions: Int = 0,
        lastReviewDate: Long = 0L,
        nextReviewDate: Long = 0L,
        tagIds: List<Long> = emptyList(),
    ): WordDto = WordDto(
        id = id,
        originalWord = originalWord,
        translation = translation,
        description = description,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        level = level,
        easeFactor = easeFactor,
        interval = interval,
        repetitions = repetitions,
        lastReviewDate = lastReviewDate,
        nextReviewDate = nextReviewDate,
        tagIds = tagIds,
    )

    private fun createUpdateWordRequest(
        originalWord: String = "hello",
        translation: String = "hallo",
        description: String = "",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        level: Int = 0,
        easeFactor: Float = 2.5f,
        interval: Int = 0,
        repetitions: Int = 0,
        lastReviewDate: Long = 0L,
        nextReviewDate: Long = 0L,
        tagIds: List<Long> = emptyList(),
    ): UpdateWordRequest = UpdateWordRequest(
        originalWord = originalWord,
        translation = translation,
        description = description,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        level = level,
        easeFactor = easeFactor,
        interval = interval,
        repetitions = repetitions,
        lastReviewDate = lastReviewDate,
        nextReviewDate = nextReviewDate,
        tagIds = tagIds,
    )
}
