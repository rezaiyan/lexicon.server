package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.WordRushGameRepository
import com.alirezaiyan.vokab.server.presentation.dto.SyncWordRushGameRequest
import com.alirezaiyan.vokab.server.presentation.dto.SyncWordRushRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WordRushServiceTest {

    private lateinit var wordRushGameRepository: WordRushGameRepository
    private lateinit var wordRushGamePersister: WordRushGamePersister
    private lateinit var wordRushService: WordRushService
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        wordRushGameRepository = mockk()
        wordRushGamePersister = mockk()
        wordRushService = WordRushService(wordRushGameRepository, wordRushGamePersister, mockk<UserRepository>().answerReferences())
        user = User(id = 1L, email = "test@example.com", name = "Test User")
    }

    @Test
    fun `syncGames includes only successfully saved game ids in response`() {
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-1" }) } returns true
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-2" }) } returns false
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-3" }) } returns true

        val response = wordRushService.syncGames(
            user.requireId(),
            SyncWordRushRequest(
                games = listOf(
                    makeGameReq("game-1"),
                    makeGameReq("game-2"),
                    makeGameReq("game-3"),
                )
            )
        )

        assertEquals(listOf("game-1", "game-3"), response.syncedGameIds)
    }

    @Test
    fun `syncGames attempts all games even when one fails`() {
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-1" }) } returns true
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-2" }) } returns false
        every { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-3" }) } returns true

        wordRushService.syncGames(
            user.requireId(),
            SyncWordRushRequest(
                games = listOf(
                    makeGameReq("game-1"),
                    makeGameReq("game-2"),
                    makeGameReq("game-3"),
                )
            )
        )

        verify(exactly = 1) { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-1" }) }
        verify(exactly = 1) { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-2" }) }
        verify(exactly = 1) { wordRushGamePersister.saveGame(user.requireId(), match { it.clientGameId == "game-3" }) }
    }

    @Test
    fun `syncGames returns empty list when all games fail`() {
        every { wordRushGamePersister.saveGame(any(), any()) } returns false

        val response = wordRushService.syncGames(
            user.requireId(),
            SyncWordRushRequest(games = listOf(makeGameReq("g1"), makeGameReq("g2")))
        )

        assertTrue(response.syncedGameIds.isEmpty())
    }

    @Test
    fun `syncGames returns empty list for empty request`() {
        val response = wordRushService.syncGames(user.requireId(), SyncWordRushRequest(games = emptyList()))

        assertTrue(response.syncedGameIds.isEmpty())
    }

    private fun makeGameReq(clientGameId: String): SyncWordRushGameRequest = SyncWordRushGameRequest(
        clientGameId = clientGameId,
        score = 100,
        totalQuestions = 10,
        correctCount = 8,
        bestStreak = 3,
        durationMs = 30000,
        avgResponseMs = 2000,
        grade = "B",
        livesRemaining = 3,
        completedNormally = true,
        playedAt = System.currentTimeMillis(),
    )
}
