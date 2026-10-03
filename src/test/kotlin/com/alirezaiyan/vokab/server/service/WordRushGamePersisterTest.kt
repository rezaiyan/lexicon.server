package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.WordRushGame
import com.alirezaiyan.vokab.server.domain.repository.WordRushGameRepository
import com.alirezaiyan.vokab.server.presentation.dto.SyncWordRushGameRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class WordRushGamePersisterTest {

    private lateinit var wordRushGameRepository: WordRushGameRepository
    private lateinit var persister: WordRushGamePersister
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        wordRushGameRepository = mockk()
        persister = WordRushGamePersister(wordRushGameRepository, mockk<UserRepository>().answerReferences())
        user = User(id = 1L, email = "test@example.com", name = "Test User")
    }

    @Test
    fun `saveGame returns true when game is successfully saved`() {
        every { wordRushGameRepository.existsByUserAndClientGameId(user, "new-game") } returns false
        every { wordRushGameRepository.save(any()) } returns mockk()

        val result = persister.saveGame(user.requireId(), makeGameReq("new-game"))

        assertTrue(result)
        verify(exactly = 1) { wordRushGameRepository.save(any<WordRushGame>()) }
    }

    @Test
    fun `saveGame returns true when game already exists without saving again`() {
        every { wordRushGameRepository.existsByUserAndClientGameId(user, "existing") } returns true

        val result = persister.saveGame(user.requireId(), makeGameReq("existing"))

        assertTrue(result)
        verify(exactly = 0) { wordRushGameRepository.save(any<WordRushGame>()) }
    }

    @Test
    fun `saveGame returns false when repository throws DataIntegrityViolationException`() {
        every { wordRushGameRepository.existsByUserAndClientGameId(user, "race-game") } returns false
        every { wordRushGameRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")

        val result = persister.saveGame(user.requireId(), makeGameReq("race-game"))

        assertFalse(result)
    }

    @Test
    fun `saveGame returns false when repository throws unexpected exception`() {
        every { wordRushGameRepository.existsByUserAndClientGameId(user, "bad-game") } returns false
        every { wordRushGameRepository.save(any()) } throws RuntimeException("unexpected DB error")

        val result = persister.saveGame(user.requireId(), makeGameReq("bad-game"))

        assertFalse(result)
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
