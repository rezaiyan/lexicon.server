package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class PushTokenServiceTest {

    private lateinit var pushTokenRepository: PushTokenRepository
    private lateinit var userRepository: UserRepository
    private lateinit var pushTokenService: PushTokenService

    @BeforeEach
    fun setUp() {
        pushTokenRepository = mockk()
        userRepository = mockk()
        pushTokenService = PushTokenService(pushTokenRepository, userRepository)
    }

    // --- registerToken ---

    @Test
    fun `registerToken should throw when user not found`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            pushTokenService.registerToken(
                userId = 99L,
                token = "token-abc",
                platform = Platform.IOS,
                deviceId = "device-1"
            )
        }
    }

    @Test
    fun `registerToken ensures the token row exists then assigns it to the user`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { pushTokenRepository.insertTokenIfAbsent(any(), any(), any(), any()) } returns 1
        every { pushTokenRepository.reassignToken(any(), any(), any(), any()) } returns 1

        // Act
        pushTokenService.registerToken(
            userId = 1L,
            token = "fcm-token-xyz",
            platform = Platform.ANDROID,
            deviceId = "device-abc"
        )

        // Assert
        verifyOrder {
            pushTokenRepository.insertTokenIfAbsent(1L, "fcm-token-xyz", Platform.ANDROID.name, "device-abc")
            pushTokenRepository.reassignToken(1L, "fcm-token-xyz", Platform.ANDROID.name, "device-abc")
        }
    }

    @Test
    fun `registerToken should pass null deviceId to repository`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { pushTokenRepository.insertTokenIfAbsent(any(), any(), any(), null) } returns 1
        every { pushTokenRepository.reassignToken(any(), any(), any(), null) } returns 1

        // Act
        pushTokenService.registerToken(
            userId = 1L,
            token = "apns-token-123",
            platform = Platform.IOS,
            deviceId = null
        )

        // Assert
        verify(exactly = 1) { pushTokenRepository.insertTokenIfAbsent(1L, "apns-token-123", Platform.IOS.name, null) }
        verify(exactly = 1) { pushTokenRepository.reassignToken(1L, "apns-token-123", Platform.IOS.name, null) }
    }

    // --- deactivateToken ---

    @Test
    fun `deactivateToken should call deactivateByToken on repository`() {
        // Arrange
        every { pushTokenRepository.deactivateByToken("stale-token") } returns 1

        // Act
        pushTokenService.deactivateToken("stale-token")

        // Assert
        verify(exactly = 1) { pushTokenRepository.deactivateByToken("stale-token") }
    }

    // --- deactivateAllUserTokens ---

    @Test
    fun `deactivateAllUserTokens should throw when user not found`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            pushTokenService.deactivateAllUserTokens(99L)
        }
    }

    @Test
    fun `deactivateAllUserTokens should deactivate all tokens for user`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { pushTokenRepository.deactivateAllByUser(user) } returns 3

        // Act
        pushTokenService.deactivateAllUserTokens(1L)

        // Assert
        verify(exactly = 1) { pushTokenRepository.deactivateAllByUser(user) }
    }

    // --- getActiveTokensForUser ---

    @Test
    fun `getActiveTokensForUser should throw when user not found`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            pushTokenService.getActiveTokensForUser(99L)
        }
    }

    @Test
    fun `getActiveTokensForUser should return active tokens`() {
        // Arrange
        val user = createUser(id = 1L)
        val token1 = createPushToken(user = user, token = "token-1", platform = Platform.IOS)
        val token2 = createPushToken(user = user, token = "token-2", platform = Platform.ANDROID)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns listOf(token1, token2)

        // Act
        val result = pushTokenService.getActiveTokensForUser(1L)

        // Assert
        assertEquals(2, result.size)
        assertEquals("token-1", result[0].token)
        assertEquals("token-2", result[1].token)
    }

    @Test
    fun `getActiveTokensForUser should return empty list when user has no active tokens`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { pushTokenRepository.findByUserAndActiveTrue(user) } returns emptyList()

        // Act
        val result = pushTokenService.getActiveTokensForUser(1L)

        // Assert
        assertEquals(0, result.size)
    }

    // --- Factory functions ---

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
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun createPushToken(
        user: User,
        token: String = "test-token",
        platform: Platform = Platform.IOS,
        deviceId: String? = null,
        active: Boolean = true
    ): PushToken = PushToken(
        user = user,
        token = token,
        platform = platform,
        deviceId = deviceId,
        active = active,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
