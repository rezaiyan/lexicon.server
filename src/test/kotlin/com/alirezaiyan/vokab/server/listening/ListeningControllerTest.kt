package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ListeningController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class ListeningControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var listeningService: ListeningService

    private val userId = 1L
    private val auth = UsernamePasswordAuthenticationToken(AuthUser(userId), null, emptyList())

    @Test
    fun `POST sync returns 200 with synced session ids`() {
        val request = SyncListeningRequest(sessions = listOf(sessionRequest("s-1")))
        `when`(listeningService.syncSessions(userId, request))
            .thenReturn(SyncListeningResponse(syncedSessionIds = listOf("s-1")))

        mockMvc.perform(
            post("/api/v1/listening/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.syncedSessionIds[0]").value("s-1"))
    }

    @Test
    fun `POST sync returns 4xx when not authenticated`() {
        mockMvc.perform(
            post("/api/v1/listening/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SyncListeningRequest(listOf(sessionRequest("s-1")))))
        )
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `POST sync returns 400 when sessions list is empty`() {
        mockMvc.perform(
            post("/api/v1/listening/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("sessions" to emptyList<Any>())))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST sync returns 400 when repeat count is zero`() {
        val invalid = SyncListeningRequest(sessions = listOf(sessionRequest("s-1").copy(repeatCount = 0)))

        mockMvc.perform(
            post("/api/v1/listening/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid))
        )
            .andExpect(status().isBadRequest)
    }

    private fun sessionRequest(id: String) = SyncListeningSessionRequest(
        clientSessionId = id,
        source = "due",
        order = "word_first",
        repeatCount = 1,
        pauseMs = 3000,
        speechRate = 1.0f,
        plannedWords = 2,
        wordsHeard = 2,
        wordsSkipped = 0,
        pauseCount = 0,
        listeningMs = 20_000,
        durationMs = 21_000,
        completedNormally = true,
        startedAt = 1_700_000_000_000,
        endedAt = 1_700_000_021_000,
        words = listOf(SyncListeningWordRequest(wordId = 7, sourceLanguage = "en", targetLanguage = "de", heardAt = 1_700_000_010_000)),
    )
}
