package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.TEST_NOW
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        every { refreshTokenRepository.findForRotationByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(1L, user.email, any()) } returns "new-access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh-token"
        every { refreshTokenHashService.createLookupHash("new-refresh-token") } returns "new-lookup-hash"
        every { refreshTokenRepository.save(any()) } answers { firstArg() }
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
        every { refreshTokenRepository.findForRotationByTokenHash(lookupHash) } returns Optional.empty()

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
        every { refreshTokenRepository.findForRotationByTokenHash(lookupHash) } returns Optional.of(revokedToken)

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
        every { refreshTokenRepository.findForRotationByTokenHash(lookupHash) } returns Optional.of(expiredToken)

        // Act & Assert
        val ex = assertThrows<AuthRejectedException> {
            tokenService.rotate(token)
        }
        assertEquals("Refresh token has expired", ex.message)
    }

    @Test
    fun `refreshAccessToken lets a database failure propagate instead of rejecting the token`() {
        every { refreshTokenHashService.createLookupHash("token") } returns "hash"
        every { refreshTokenRepository.findForRotationByTokenHash("hash") } throws IllegalStateException("connection refused")

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
        every { refreshTokenRepository.findForRotationByTokenHash(lookupHash) } returns Optional.of(tokenEntity)
        every { jwtTokenProvider.generateAccessToken(4L, user.email, any()) } returns "new-access"
        every { refreshTokenHashService.generateSecureToken(32) } returns "new-refresh"
        every { refreshTokenHashService.createLookupHash("new-refresh") } returns "new-hash"
        every { refreshTokenRepository.save(any()) } answers { firstArg() }
        every { jwtTokenProvider.getExpirationTime() } returns 3600L

        // Act
        val result = tokenService.rotate(token)

        // Assert
        assertEquals(4L, result.user.id)
        assertEquals("test@example.com", result.user.email)
        assertEquals("Test User", result.user.name)
    }

    // ── rotation: one-time use, grace window, reuse detection ─────────────────

    private fun stubRotation(tokenEntity: RefreshToken, presented: String = "presented") {
        every { refreshTokenHashService.createLookupHash(presented) } returns tokenEntity.tokenHash
        every { refreshTokenRepository.findForRotationByTokenHash(tokenEntity.tokenHash) } returns Optional.of(tokenEntity)
        every { refreshTokenRepository.revokeFamily(any()) } returns 2
        stubTokenGeneration(tokenEntity.user)
    }

    @Test
    fun `rotate marks a first-used token as used and keeps its expiry for reuse detection`() {
        val original = createRefreshToken(user = createUser(id = 30L), tokenHash = "h30")
        stubRotation(original)

        tokenService.rotate("presented")

        assertEquals(TEST_NOW, original.usedAt)
        assertEquals(TEST_NOW.plusSeconds(3600), original.expiresAt)
    }

    @Test
    fun `rotate issues the successor in the same family`() {
        val original = createRefreshToken(user = createUser(id = 31L), tokenHash = "h31", familyId = "fam-31")
        stubRotation(original)
        val saved = mutableListOf<RefreshToken>()
        every { refreshTokenRepository.save(capture(saved)) } answers { firstArg() }

        tokenService.rotate("presented")

        val successor = saved.first { it !== original }
        assertEquals("fam-31", successor.familyId)
        assertEquals("lookup-hash", successor.tokenHash)
    }

    @Test
    fun `rotate audits the refresh with the real family id`() {
        val user = createUser(id = 32L)
        val original = createRefreshToken(user = user, tokenHash = "h32", familyId = "fam-32")
        stubRotation(original)

        tokenService.rotate("presented")

        verify { auditLogService.logRefresh(32L, user.email, "fam-32", null, null) }
    }

    @Test
    fun `rotate within the grace window issues new tokens without moving the first-use time`() {
        val firstUse = TEST_NOW.minusSeconds(10) // grace is 30s
        val original = createRefreshToken(user = createUser(id = 33L), tokenHash = "h33", usedAt = firstUse)
        stubRotation(original)

        val result = tokenService.rotate("presented")

        assertEquals("refresh-token", result.refreshToken)
        assertEquals(firstUse, original.usedAt)
        verify(exactly = 0) { refreshTokenRepository.revokeFamily(any()) }
    }

    @Test
    fun `rotate after the grace window revokes the whole family`() {
        val original = createRefreshToken(
            user = createUser(id = 34L), tokenHash = "h34", familyId = "fam-34", usedAt = TEST_NOW.minusSeconds(31),
        )
        stubRotation(original)

        val ex = assertThrows<AuthRejectedException> { tokenService.rotate("presented") }

        assertEquals("Refresh token has been revoked", ex.message)
        verify(exactly = 1) { refreshTokenRepository.revokeFamily("fam-34") }
    }

    @Test
    fun `rotate after the grace window audits the token reuse`() {
        val original = createRefreshToken(
            user = createUser(id = 35L), tokenHash = "h35", familyId = "fam-35", usedAt = TEST_NOW.minusSeconds(60),
        )
        stubRotation(original)

        assertThrows<AuthRejectedException> { tokenService.rotate("presented") }

        verify(exactly = 1) { auditLogService.logTokenReuse(35L, "fam-35", null) }
    }

    @Test
    fun `rotate after the grace window issues no new tokens`() {
        val original = createRefreshToken(user = createUser(id = 36L), tokenHash = "h36", usedAt = TEST_NOW.minusSeconds(60))
        stubRotation(original)

        assertThrows<AuthRejectedException> { tokenService.rotate("presented") }

        verify(exactly = 0) { jwtTokenProvider.generateAccessToken(any(), any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.save(any()) }
    }

    @Test
    fun `rotate keeps the family revocation when the request is rejected`() {
        val rotate = TokenService::class.java.getMethod("rotate", String::class.java)
        val tx = rotate.getAnnotation(org.springframework.transaction.annotation.Transactional::class.java)

        assertTrue(AuthRejectedException::class in tx.noRollbackFor)
    }

    @Test
    fun `rotate rejects the refresh token of a deactivated user`() {
        val user = createUser(id = 37L).also { it.active = false }
        val original = createRefreshToken(user = user, tokenHash = "h37")
        stubRotation(original)

        assertThrows<AuthRejectedException> { tokenService.rotate("presented") }
        verify(exactly = 0) { jwtTokenProvider.generateAccessToken(any(), any(), any()) }
    }

    @Test
    fun `issue starts a new family for every sign-in`() {
        val user = createUser(id = 38L)
        stubTokenGeneration(user)
        val saved = mutableListOf<RefreshToken>()
        every { refreshTokenRepository.save(capture(saved)) } answers { firstArg() }

        tokenService.issue(user, 38L)
        tokenService.issue(user, 38L)

        assertNotEquals(saved[0].familyId, saved[1].familyId)
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
        verify(exactly = 1) { auditLogService.logLogout(userId, user.email, lookupHash, null) }
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
    fun `logoutAll revokes access tokens issued up to now`() {
        val userId = 24L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        tokenService.logoutAll(userId)

        verify(exactly = 1) { userRepository.updateTokensValidAfter(userId, TEST_NOW) }
    }

    @Test
    fun `logoutAll evicts the cached access decision`() {
        val userId = 25L
        val user = createUser(id = userId)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any()) } returns emptyList()
        every { refreshTokenRepository.revokeAllByUser(user) } returns 1

        tokenService.logoutAll(userId)

        verify(exactly = 1) { userAccessCache.evict(userId) }
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
