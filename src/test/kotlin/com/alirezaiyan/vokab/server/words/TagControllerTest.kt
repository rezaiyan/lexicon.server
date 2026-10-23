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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(TagController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class TagControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var tagService: TagService

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

    // ── GET /api/v1/tags ──────────────────────────────────────────────────────

    @Test
    fun `GET tags should return 200 with tag list`() {
        val tags = listOf(
            createTagDto(id = 1L, name = "verbs"),
            createTagDto(id = 2L, name = "nouns"),
        )
        `when`(tagService.list(mockUser.requireId())).thenReturn(tags)

        mockMvc.perform(
            get("/api/v1/tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].name").value("verbs"))
            .andExpect(jsonPath("$.data[1].name").value("nouns"))
    }

    @Test
    fun `GET tags should return 4xx when not authenticated`() {
        mockMvc.perform(
            get("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().is4xxClientError)
    }

    // ── POST /api/v1/tags ─────────────────────────────────────────────────────

    @Test
    fun `POST tags should return 201 when tag created`() {
        val request = CreateTagRequest(name = "phrases")
        val createdTag = createTagDto(id = 10L, name = "phrases")
        `when`(tagService.create(mockUser.requireId(), "phrases")).thenReturn(createdTag)

        mockMvc.perform(
            post("/api/v1/tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.name").value("phrases"))
    }

    @Test
    fun `POST tags should return 400 when name is blank`() {
        val request = CreateTagRequest(name = "   ")

        mockMvc.perform(
            post("/api/v1/tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST tags should return 400 when duplicate tag`() {
        val request = CreateTagRequest(name = "verbs")
        `when`(tagService.create(mockUser.requireId(), "verbs"))
            .thenThrow(IllegalArgumentException("A tag named 'verbs' already exists"))

        mockMvc.perform(
            post("/api/v1/tags")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── PUT /api/v1/tags/{id} ─────────────────────────────────────────────────

    @Test
    fun `PUT tag rename should return 200 when renamed`() {
        val request = RenameTagRequest(name = "adjectives")
        val renamedTag = createTagDto(id = 1L, name = "adjectives")
        `when`(tagService.rename(mockUser.requireId(), 1L, "adjectives")).thenReturn(renamedTag)

        mockMvc.perform(
            put("/api/v1/tags/1")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("adjectives"))
    }

    @Test
    fun `PUT tag rename should return 404 when tag not found`() {
        val request = RenameTagRequest(name = "adjectives")
        `when`(tagService.rename(mockUser.requireId(), 99L, "adjectives"))
            .thenThrow(NoSuchElementException("Tag not found"))

        mockMvc.perform(
            put("/api/v1/tags/99")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── DELETE /api/v1/tags/{id} ──────────────────────────────────────────────

    @Test
    fun `DELETE tag should return 204 when deleted`() {
        `when`(tagService.delete(mockUser.requireId(), 1L)).then { }

        mockMvc.perform(
            delete("/api/v1/tags/1")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE tag should return 404 when tag not found`() {
        doThrow(NoSuchElementException("Tag not found"))
            .`when`(tagService).delete(mockUser.requireId(), 99L)

        mockMvc.perform(
            delete("/api/v1/tags/99")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createTagDto(
        id: Long = 1L,
        name: String = "test-tag",
        wordCount: Long = 0L,
    ): TagDto = TagDto(
        id = id,
        name = name,
        wordCount = wordCount,
        createdAt = Instant.now().toEpochMilli(),
        updatedAt = Instant.now().toEpochMilli(),
    )
}
