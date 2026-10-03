package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.User
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class EventControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var eventService: EventService

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

    // ── POST /api/v1/events ────────────────────────────────────────────────────

    @Test
    fun `POST events should return 200 when event tracked successfully`() {
        val request = createTrackEventRequest(eventName = "word_added")
        `when`(eventService.track(1L, request)).then { }

        mockMvc.perform(
            post("/api/v1/events")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Event tracked"))
    }

    @Test
    fun `POST events should return 4xx when not authenticated`() {
        val request = createTrackEventRequest(eventName = "word_added")

        mockMvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `POST events should return 400 when event name is blank`() {
        val request = createTrackEventRequest(eventName = "")

        mockMvc.perform(
            post("/api/v1/events")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST events should return 200 even when event service throws`() {
        // The EventController absorbs all exceptions — analytics failures must never break the client
        val request = createTrackEventRequest(eventName = "word_added")
        `when`(eventService.track(1L, request)).thenThrow(RuntimeException("tracking down"))

        mockMvc.perform(
            post("/api/v1/events")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Event accepted"))
    }

    @Test
    fun `POST events should include properties in tracked event`() {
        val properties = mapOf("source" to "home_screen", "count" to "5")
        val request = createTrackEventRequest(eventName = "review_completed", properties = properties)
        `when`(eventService.track(1L, request)).then { }

        mockMvc.perform(
            post("/api/v1/events")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        verify(eventService).track(1L, request)
    }

    @Test
    fun `POST events should call service with platform and app version when provided`() {
        val request = createTrackEventRequest(
            eventName = "app_opened",
            platform = "ios",
            appVersion = "2.1.0",
        )
        `when`(eventService.track(1L, request)).then { }

        mockMvc.perform(
            post("/api/v1/events")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        verify(eventService).track(1L, request)
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createTrackEventRequest(
        eventName: String = "test_event",
        properties: Map<String, String> = emptyMap(),
        platform: String? = null,
        appVersion: String? = null,
        clientTimestampMs: Long = Instant.now().toEpochMilli(),
    ): TrackEventRequest = TrackEventRequest(
        eventName = eventName,
        properties = properties,
        platform = platform,
        appVersion = appVersion,
        clientTimestampMs = clientTimestampMs,
    )
}
