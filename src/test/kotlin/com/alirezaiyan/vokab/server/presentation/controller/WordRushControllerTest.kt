package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.WordRushService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class WordRushControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var wordRushService: WordRushService

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

    private val auth = UsernamePasswordAuthenticationToken(mockUser, null, emptyList())

    // -- POST /api/v1/word-rush/sync ------------------------------------------

    @Test
    fun `POST sync should return 200 with synced game ids when successful`() {
        val request = createSyncRequest()
        `when`(wordRushService.syncGames(mockUser, request))
            .thenReturn(SyncWordRushResponse(syncedGameIds = listOf("game-1")))

        mockMvc.perform(
            post("/api/v1/word-rush/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.syncedGameIds[0]").value("game-1"))
    }

    @Test
    fun `POST sync should return 500 when service throws exception`() {
        val request = createSyncRequest()
        `when`(wordRushService.syncGames(mockUser, request))
            .thenThrow(RuntimeException("sync failed"))

        mockMvc.perform(
            post("/api/v1/word-rush/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST sync should return 4xx when not authenticated`() {
        val request = createSyncRequest()

        mockMvc.perform(
            post("/api/v1/word-rush/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().is4xxClientError)
    }

    @Test
    fun `POST sync should return 400 when games list is empty`() {
        val invalidRequest = mapOf("games" to emptyList<Any>())

        mockMvc.perform(
            post("/api/v1/word-rush/sync")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // -- GET /api/v1/word-rush/insights ---------------------------------------

    @Test
    fun `GET insights should return 200 with Word Rush insights`() {
        `when`(wordRushService.getInsights(mockUser)).thenReturn(createInsightsResponse())

        mockMvc.perform(
            get("/api/v1/word-rush/insights")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalGames").value(10))
            .andExpect(jsonPath("$.data.bestStreakEver").value(7))
            .andExpect(jsonPath("$.data.avgScore").value(85.5))
            .andExpect(jsonPath("$.data.completionRatePercent").value(80.0))
    }

    @Test
    fun `GET insights should return 500 when service throws exception`() {
        `when`(wordRushService.getInsights(mockUser))
            .thenThrow(RuntimeException("database error"))

        mockMvc.perform(
            get("/api/v1/word-rush/insights")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `GET insights should return 4xx when not authenticated`() {
        mockMvc.perform(
            get("/api/v1/word-rush/insights")
        )
            .andExpect(status().is4xxClientError)
    }

    // -- GET /api/v1/word-rush/history ----------------------------------------

    @Test
    fun `GET history should return 200 with game history`() {
        `when`(wordRushService.getHistory(mockUser)).thenReturn(
            listOf(
                WordRushGameResponse(
                    clientGameId = "game-1",
                    score = 120,
                    totalQuestions = 15,
                    correctCount = 12,
                    bestStreak = 5,
                    durationMs = 45000,
                    avgResponseMs = 3000,
                    grade = "A",
                    livesRemaining = 2,
                    completedNormally = true,
                    playedAt = 1700000000000,
                )
            )
        )

        mockMvc.perform(
            get("/api/v1/word-rush/history")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].clientGameId").value("game-1"))
            .andExpect(jsonPath("$.data[0].score").value(120))
            .andExpect(jsonPath("$.data[0].grade").value("A"))
    }

    @Test
    fun `GET history should return 500 when service throws exception`() {
        `when`(wordRushService.getHistory(mockUser))
            .thenThrow(RuntimeException("query failed"))

        mockMvc.perform(
            get("/api/v1/word-rush/history")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `GET history should return 4xx when not authenticated`() {
        mockMvc.perform(
            get("/api/v1/word-rush/history")
        )
            .andExpect(status().is4xxClientError)
    }

    // -- factory functions ----------------------------------------------------

    private fun createSyncRequest(): SyncWordRushRequest = SyncWordRushRequest(
        games = listOf(
            SyncWordRushGameRequest(
                clientGameId = "game-1",
                score = 120,
                totalQuestions = 15,
                correctCount = 12,
                bestStreak = 5,
                durationMs = 45000,
                avgResponseMs = 3000,
                grade = "A",
                livesRemaining = 2,
                completedNormally = true,
                playedAt = 1700000000000,
            )
        )
    )

    private fun createInsightsResponse(): WordRushInsightsResponse = WordRushInsightsResponse(
        totalGames = 10,
        totalCompleted = 8,
        completionRatePercent = 80.0,
        bestStreakEver = 7,
        avgScore = 85.5,
        avgAccuracyPercent = 75.0,
        totalTimePlayedMs = 450000,
        avgDurationMs = 45000.0,
        avgResponseMs = 3000.0,
    )
}
