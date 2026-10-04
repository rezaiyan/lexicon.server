package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.TEST_NOW
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class TokenServiceTest : AuthServiceTestFixture() {

    // ── refreshAccessToken ────────────────────────────────────────────────────

    @Test
    fun `refreshAccessToken should return new tokens when refresh token is valid`() {
        // Arrange
        val user = createUser(id = 1L)
        val token = "valid-refresh-token"
        val lookupHash = "hashed-token"
        val tokenEntity = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = TEST_NOW.plusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(1L, user.email, any()) } returns "new-access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh-token"
        every { refreshTokenHashService.createLookupHash("new-refresh-token") } returns "new-lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L

        // Act
        val result = tokenService.rotate(token)

        // Assert
        assertEquals("new-access-token", result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)
        assertEquals(86400L, result.expiresIn)
        verify(exactly = 2) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `refreshAccessToken should throw when refresh token is not found`() {
        // Arrange
        val token = "unknown-token"
        val lookupHash = "unknown-hash"
        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<AuthRejectedException> {
            tokenService.rotate(token)
        }
        assertEquals("Refresh token not found", ex.message)
    }

    @Test
    fun `refreshAccessToken should throw when refresh token has been revoked`() {
        // Arrange
        val user = createUser(id = 2L)
        val token = "revoked-token"
        val lookupHash = "revoked-hash"
        val revokedToken = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = TEST_NOW.plusSeconds(3600), revoked = true)

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(revokedToken)

        // Act & Assert
        val ex = assertThrows<AuthRejectedException> {
            tokenService.rotate(token)
        }
        assertEquals("Refresh token has been revoked", ex.message)
    }

    @Test
    fun `refreshAccessToken should throw when refresh token has expired`() {
        // Arrange
        val user = createUser(id = 3L)
        val token = "expired-token"
        val lookupHash = "expired-hash"
        val expiredToken = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = TEST_NOW.minusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(expiredToken)

        // Act & Assert
        val ex = assertThrows<AuthRejectedException> {
            tokenService.rotate(token)
        }
        assertEquals("Refresh token has expired", ex.message)
    }

    @Test
    fun `refreshAccessToken lets a database failure propagate instead of rejecting the token`() {
        every { refreshTokenHashService.createLookupHash("token") } returns "hash"
        every { refreshTokenRepository.findByTokenHash("hash") } throws IllegalStateException("connection refused")

        assertThrows<IllegalStateException> { tokenService.rotate("token") }
    }

    @Test
    fun `refreshAccessToken should include user dto in response`() {
        // Arrange
        val user = createUser(id = 4L, email = "test@example.com", name = "Test User")
        val token = "valid-token"
        val lookupHash = "valid-hash"
        val tokenEntity = createRefreshToken(user = user, tokenHash = lookupHash,
            expiresAt = TEST_NOW.plusSeconds(3600))

        every { refreshTokenHashService.createLookupHash(token) } returns lookupHash
        every { refreshTokenRepository.findByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(4L, user.email, any()) } returns "new-access"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh"
        every { refreshTokenHashService.createLookupHash("new-refresh") } returns "new-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 3600L

        // Act
        val result = tokenService.rotate(token)

        // Assert
        assertEquals(4L, result.user.id)
        assertEquals("test@example.com", result.user.email)
        assertEquals("Test User", result.user.name)
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    fun `logout should revoke the refresh token by its lookup hash`() {
        // Arrange
        val userId = 10L
        val refreshToken = "my-refresh-token"
        val lookupHash = "my-hash"
        val user = createUser(id = userId)

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.of(user)

        // Act
        tokenService.logout(userId, refreshToken)

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeByTokenHash(lookupHash) }
    }

    @Test
    fun `logout should call audit log when user is found`() {
        // Arrange
        val userId = 11L
        val refreshToken = "audit-token"
        val lookupHash = "audit-hash"
        val user = createUser(id = userId)

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.of(user)

        // Act
        tokenService.logout(userId, refreshToken)

        // Assert
        verify(exactly = 1) { auditLogService.logLogout(userId, user.email, refreshToken, null) }
    }

    @Test
    fun `logout should still revoke token even when user is not found in repository`() {
        // Arrange
        val userId = 12L
        val refreshToken = "orphan-token"
        val lookupHash = "orphan-hash"

        every { refreshTokenHashService.createLookupHash(refreshToken) } returns lookupHash
        every { refreshTokenRepository.revokeByTokenHash(lookupHash) } returns 1
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act — must not throw even if the user row is gone
        assertDoesNotThrow { tokenService.logout(userId, refreshToken) }

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeByTokenHash(lookupHash) }
        verify(exactly = 0) { auditLogService.logLogout(any(), any(), any(), any()) }
    }

    // ── logoutAll ─────────────────────────────────────────────────────────────

    @Test
    fun `logoutAll should revoke all tokens for user`() {
        // Arrange
        val userId = 20L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 3

        // Act
        tokenService.logoutAll(userId)

        // Assert
        verify(exactly = 1) { refreshTokenRepository.revokeAllByUser(user) }
    }

    @Test
    fun `logoutAll should call audit log after revoking all tokens`() {
        // Arrange
        val userId = 21L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 2

        // Act
        tokenService.logoutAll(userId)

        // Assert
        verify(exactly = 1) { auditLogService.logLogoutAll(userId, user.email, null) }
    }

    @Test
    fun `logoutAll should throw when user is not found`() {
        // Arrange
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            tokenService.logoutAll(userId)
        }
        assertEquals("User not found", ex.message)
    }

    @Test
    fun `logoutAll should still revoke tokens when push notification fails`() {
        // Arrange
        val userId = 22L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every {
            pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
        } throws RuntimeException("FCM is down")
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        // Act — push failure must not propagate
        assertDoesNotThrow { tokenService.logoutAll(userId) }

        // Assert — revocation still happened
        verify(exactly = 1) { refreshTokenRepository.revokeAllByUser(user) }
    }

    @Test
    fun `logoutAll should send push notification to all devices`() {
        // Arrange
        val userId = 23L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        // Act
        tokenService.logoutAll(userId)

        // Assert
        verify(exactly = 1) {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = any(),
                body = any(),
                data = any(),
                category = any()
            )
        }
    }
}
