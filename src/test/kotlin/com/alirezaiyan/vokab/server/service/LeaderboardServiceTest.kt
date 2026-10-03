package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class LeaderboardServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var wordRepository: WordRepository
    private lateinit var aliasGenerator: AliasGenerator
    private lateinit var leaderboardService: LeaderboardService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        wordRepository = mockk()
        aliasGenerator = AliasGenerator()
        leaderboardService = LeaderboardService(userRepository, wordRepository, aliasGenerator, "")
    }

    @Test
    fun `getLeaderboard returns users ranked by composite score`() {
        val user1 = createUser(id = 1L, currentStreak = 10, longestStreak = 20)
        val user2 = createUser(id = 2L, currentStreak = 5, longestStreak = 30)
        val user3 = createUser(id = 3L, currentStreak = 0, longestStreak = 0)

        every { userRepository.findTopUsersByScore(any(), any()) } returns listOf(user1, user2, user3)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L, 2L, 3L)) } returns listOf(
            arrayOf(1L as Any, 50L as Any),
            arrayOf(2L as Any, 10L as Any)
        )

        val result = leaderboardService.getLeaderboard(user1)

        assertEquals(3, result.entries.size)
        assertEquals(50, result.entries[0].masteredWords)
        assertEquals(10, result.entries[1].masteredWords)
        assertEquals(0, result.entries[2].masteredWords)
        assertNull(result.userEntry)
    }

    @Test
    fun `getLeaderboard marks requesting user correctly`() {
        val user1 = createUser(id = 1L, currentStreak = 10, longestStreak = 20)
        val user2 = createUser(id = 2L, currentStreak = 5, longestStreak = 30)

        every { userRepository.findTopUsersByScore(any(), any()) } returns listOf(user1, user2)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L, 2L)) } returns emptyList()

        val result = leaderboardService.getLeaderboard(user2)

        assertFalse(result.entries[0].isCurrentUser)
        assertTrue(result.entries[1].isCurrentUser)
    }

    @Test
    fun `getLeaderboard provides user entry when not in top list`() {
        val topUser = createUser(id = 1L, currentStreak = 50, longestStreak = 100)
        val requestingUser = createUser(id = 99L, currentStreak = 1, longestStreak = 2)

        every { userRepository.findTopUsersByScore(any(), any()) } returns listOf(topUser)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L)) } returns listOf(
            arrayOf(1L as Any, 80L as Any)
        )
        every { wordRepository.countMasteredWordsByUserId(99L) } returns 5L
        // score = 5*10 + 1*3 + 2*2 = 57
        every { userRepository.findUserRankByScore(57L, any()) } returns 42L

        val result = leaderboardService.getLeaderboard(requestingUser)

        assertNotNull(result.userEntry)
        assertEquals(42, result.userEntry!!.rank)
        assertEquals(5, result.userEntry.masteredWords)
        assertTrue(result.userEntry.isCurrentUser)
    }

    @Test
    fun `getLeaderboard uses display alias when available`() {
        val userWithAlias = createUser(id = 1L, currentStreak = 5, longestStreak = 10, displayAlias = "CoolLearner42")

        every { userRepository.findTopUsersByScore(any(), any()) } returns listOf(userWithAlias)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L)) } returns emptyList()

        val result = leaderboardService.getLeaderboard(userWithAlias)

        assertEquals("CoolLearner42", result.entries[0].displayName)
    }

    @Test
    fun `getLeaderboard generates alias when display alias is null`() {
        val userWithoutAlias = createUser(id = 1L, currentStreak = 5, longestStreak = 10, displayAlias = null)

        every { userRepository.findTopUsersByScore(any(), any()) } returns listOf(userWithoutAlias)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L)) } returns emptyList()

        val result = leaderboardService.getLeaderboard(userWithoutAlias)

        assertTrue(result.entries[0].displayName.isNotEmpty())
    }

    @Test
    fun `getLeaderboard handles empty leaderboard`() {
        val requestingUser = createUser(id = 1L, currentStreak = 0, longestStreak = 0)

        every { userRepository.findTopUsersByScore(any(), any()) } returns emptyList()
        every { wordRepository.countMasteredWordsByUserId(1L) } returns 0L
        // score = 0*10 + 0*3 + 0*2 = 0
        every { userRepository.findUserRankByScore(0L, any()) } returns 1L

        val result = leaderboardService.getLeaderboard(requestingUser)

        assertTrue(result.entries.isEmpty())
        assertNotNull(result.userEntry)
        assertEquals(1, result.userEntry!!.rank)
    }

    @Test
    fun `getLeaderboard excludes CI test user from leaderboard`() {
        val ciEmail = "ci-maestro@test.vokab.dev"
        val serviceWithCiEmail = LeaderboardService(userRepository, wordRepository, aliasGenerator, ciEmail)
        val regularUser = createUser(id = 1L, currentStreak = 5, longestStreak = 10)

        every { userRepository.findTopUsersByScore(any(), listOf(ciEmail)) } returns listOf(regularUser)
        every { wordRepository.countMasteredWordsByUserIds(listOf(1L)) } returns emptyList()

        serviceWithCiEmail.getLeaderboard(regularUser)

        verify { userRepository.findTopUsersByScore(any(), listOf(ciEmail)) }
    }

    @Test
    fun `alias generator produces deterministic results`() {
        val alias1 = aliasGenerator.generate(42L)
        val alias2 = aliasGenerator.generate(42L)
        assertEquals(alias1, alias2)
    }

    private fun createUser(
        id: Long,
        currentStreak: Int,
        longestStreak: Int,
        displayAlias: String? = null
    ): User = User(
        id = id,
        email = "user$id@example.com",
        name = "User $id",
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        displayAlias = displayAlias,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
