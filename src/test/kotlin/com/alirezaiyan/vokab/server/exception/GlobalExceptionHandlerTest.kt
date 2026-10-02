package com.alirezaiyan.vokab.server.exception

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.controller.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.service.WordService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * Tests for [GlobalExceptionHandler] using a real Spring MVC context.
 *
 * [WordService] is mocked so that individual endpoints can be forced to throw
 * whichever exception we want to verify the handler maps correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class GlobalExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

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
        updatedAt = Instant.now()
    )

    private val auth = UsernamePasswordAuthenticationToken(mockUser, null, emptyList())

    // ── IllegalArgumentException → 400 ───────────────────────────────────────

    @Test
    fun `should return 400 with success=false when IllegalArgumentException is thrown`() {
        // Arrange
        doThrow(IllegalArgumentException("Word does not belong to user"))
            .`when`(wordService).delete(mockUser, 1L)

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/words/1")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `should include the exception message in response body for IllegalArgumentException`() {
        // Arrange
        doThrow(IllegalArgumentException("Forbidden: word belongs to another user"))
            .`when`(wordService).delete(mockUser, 2L)

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/words/2")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Forbidden: word belongs to another user"))
    }

    // ── NoSuchElementException → 404 ─────────────────────────────────────────

    @Test
    fun `should return 404 with success=false when NoSuchElementException is thrown`() {
        // Arrange
        doThrow(NoSuchElementException("Word not found"))
            .`when`(wordService).delete(mockUser, 99L)

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/words/99")
                .with(authentication(auth))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `should include resource not found prefix in response for NoSuchElementException`() {
        // Arrange
        doThrow(NoSuchElementException("Word not found"))
            .`when`(wordService).delete(mockUser, 99L)

        // Act + Assert
        mockMvc.perform(
            delete("/api/v1/words/99")
                .with(authentication(auth))
        )
            .andExpect(jsonPath("$.message").value("Resource not found"))
    }

    // ── DataIntegrityViolationException → 409 ────────────────────────────────

    @Test
    fun `should return 409 with success=false when DataIntegrityViolationException is thrown`() {
        // Arrange
        doThrow(DataIntegrityViolationException("Duplicate key"))
            .`when`(wordService).list(mockUser, null)

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/words")
                .with(authentication(auth))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Resource already exists"))
    }

    // ── MethodArgumentNotValidException → 400 ────────────────────────────────

    @Test
    fun `should return 400 with success=false when request body validation fails`() {
        // The UpsertWordsRequest has @NotEmpty on words — sending an empty list triggers
        // MethodArgumentNotValidException which the handler maps to 400.
        val invalidBody = """{"words": []}"""

        mockMvc.perform(
            post("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `should include validation failed prefix in response for MethodArgumentNotValidException`() {
        val invalidBody = """{"words": []}"""

        mockMvc.perform(
            post("/api/v1/words")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody)
        )
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Validation failed")))
    }

    // ── Spring MVC request errors → their own 4xx status ─────────────────────

    @Test
    fun `should return 404 for an unknown endpoint`() {
        mockMvc.perform(
            get("/api/v1/does-not-exist")
                .with(authentication(auth))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `should return 400 naming the parameter when a required query parameter is missing`() {
        mockMvc.perform(
            get("/api/v1/analytics/heatmap")
                .param("start", "0")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("end")))
    }

    @Test
    fun `should return 400 naming the parameter when a query parameter has the wrong type`() {
        mockMvc.perform(
            get("/api/v1/analytics/heatmap")
                .param("start", "yesterday")
                .param("end", "0")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Invalid value for parameter 'start'"))
    }

    @Test
    fun `should return 405 when the HTTP method is not supported`() {
        mockMvc.perform(
            delete("/api/v1/words")
                .with(authentication(auth))
        )
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── Unhandled exception → 500 ─────────────────────────────────────────────

    @Test
    fun `should return 500 with success=false for unexpected RuntimeException`() {
        // Arrange
        doThrow(RuntimeException("Unexpected internal error"))
            .`when`(wordService).list(mockUser, null)

        // Act + Assert
        mockMvc.perform(
            get("/api/v1/words")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }
}
