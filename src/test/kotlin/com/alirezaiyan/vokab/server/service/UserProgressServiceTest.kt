package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ProgressRow
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserProgressServiceTest {

    private lateinit var wordRepository: WordRepository
    private lateinit var userProgressService: UserProgressService

    @BeforeEach
    fun setUp() {
        wordRepository = mockk()
        userProgressService = UserProgressService(wordRepository, clock = fixedClock())
    }

    @Test
    fun `should return all zeros for user with no words`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns emptyList()

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(0, result.totalWords)
        assertEquals(0, result.dueCards)
        assertEquals(0, result.level0Count)
        assertEquals(0, result.level1Count)
        assertEquals(0, result.level2Count)
        assertEquals(0, result.level3Count)
        assertEquals(0, result.level4Count)
        assertEquals(0, result.level5Count)
        assertEquals(0, result.level6Count)
    }

    @Test
    fun `should count words by level correctly`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 2, dueCount = 0),
            progressRow(level = 1, wordCount = 1, dueCount = 0),
            progressRow(level = 3, wordCount = 1, dueCount = 0),
            progressRow(level = 6, wordCount = 1, dueCount = 0),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(2, result.level0Count)
        assertEquals(1, result.level1Count)
        assertEquals(0, result.level2Count)
        assertEquals(1, result.level3Count)
        assertEquals(0, result.level4Count)
        assertEquals(0, result.level5Count)
        assertEquals(1, result.level6Count)
    }

    @Test
    fun `should count due cards correctly based on dueCount from aggregate`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 3, dueCount = 3),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(3, result.dueCards)
    }

    @Test
    fun `should not count non-due cards`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 2, dueCount = 0),
            progressRow(level = 1, wordCount = 2, dueCount = 0),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(0, result.dueCards)
    }

    @Test
    fun `should coerce word level below 0 to level 0`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = -1, wordCount = 2, dueCount = 0),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(2, result.level0Count)
        assertEquals(0, result.level1Count)
    }

    @Test
    fun `should coerce word level above 6 to level 6`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 7, wordCount = 1, dueCount = 0),
            progressRow(level = 99, wordCount = 1, dueCount = 0),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(2, result.level6Count)
        assertEquals(0, result.level5Count)
    }

    @Test
    fun `should count total words correctly`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 4, dueCount = 0),
            progressRow(level = 1, wordCount = 3, dueCount = 0),
            progressRow(level = 2, wordCount = 3, dueCount = 0),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(10, result.totalWords)
    }

    @Test
    fun `should handle words at all levels`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 1, dueCount = 1),
            progressRow(level = 1, wordCount = 1, dueCount = 0),
            progressRow(level = 2, wordCount = 1, dueCount = 1),
            progressRow(level = 3, wordCount = 1, dueCount = 0),
            progressRow(level = 4, wordCount = 1, dueCount = 1),
            progressRow(level = 5, wordCount = 1, dueCount = 0),
            progressRow(level = 6, wordCount = 1, dueCount = 1),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(7, result.totalWords)
        assertEquals(4, result.dueCards)
        assertEquals(1, result.level0Count)
        assertEquals(1, result.level1Count)
        assertEquals(1, result.level2Count)
        assertEquals(1, result.level3Count)
        assertEquals(1, result.level4Count)
        assertEquals(1, result.level5Count)
        assertEquals(1, result.level6Count)
    }

    @Test
    fun `should count due cards correctly when mixed past and future dates`() {
        val user = createUser()
        every { wordRepository.findProgressRowsByUserId(1L, any()) } returns listOf(
            progressRow(level = 0, wordCount = 2, dueCount = 1),
            progressRow(level = 1, wordCount = 1, dueCount = 1),
        )

        val result = userProgressService.calculateProgressStats(user.requireId())

        assertEquals(2, result.dueCards)
        assertEquals(3, result.totalWords)
    }

    // --- factory functions ---

    private fun progressRow(level: Int, wordCount: Long, dueCount: Long): ProgressRow =
        object : ProgressRow {
            override fun getLevel(): Int = level
            override fun getWordCount(): Long = wordCount
            override fun getDueCount(): Long = dueCount
        }

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com"
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )
}
