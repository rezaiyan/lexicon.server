package com.alirezaiyan.vokab.server.auth

import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class AccountDeletionServiceTest : AuthServiceTestFixture() {

    // ── deleteAccount ─────────────────────────────────────────────────────────

    @Test
    fun `deleteAccount should delete all user data and the user record`() {
        // Arrange
        val userId = 30L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        accountDeletionService.deleteAccount(userId)

        // Assert — user row is deleted last
        verify(exactly = 1) { userRepository.delete(user) }
    }

    @Test
    fun `deleteAccount notifies devices, purges owned data, audits, then deletes the user row`() {
        val userId = 31L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        accountDeletionService.deleteAccount(userId)

        verifyOrder {
            pushNotificationService.sendNotificationToUser(userId, any(), any(), any(), any())
            userDataPurger.purge(userId)
            auditLogService.logAccountDeletion(userId, user.email, null)
            userRepository.delete(user)
        }
    }

    @Test
    fun `deleteAccount deletes the RevenueCat subscriber when the user has one`() {
        val userId = 32L
        val user = createUser(id = userId, googleId = null).also { it.mirrorRevenueCatUserId("rc-32") }
        stubDeleteAccount(userId, user)

        accountDeletionService.deleteAccount(userId)

        verify(exactly = 1) { revenueCatClient.deleteSubscriber("rc-32") }
    }

    @Test
    fun `deleteAccount skips RevenueCat when the user never purchased`() {
        val userId = 33L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        accountDeletionService.deleteAccount(userId)

        verify(exactly = 0) { revenueCatClient.deleteSubscriber(any()) }
    }

    @Test
    fun `deleteAccount should throw when user is not found`() {
        // Arrange
        val userId = 999L
        every { userRepository.findById(userId) } returns Optional.empty()

        // Act & Assert
        val ex = assertThrows<IllegalArgumentException> {
            accountDeletionService.deleteAccount(userId)
        }
        assertEquals("User not found", ex.message)
    }

    @Test
    fun `deleteAccount should continue with deletion when push notification fails`() {
        // Arrange
        val userId = 35L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user, pushThrows = true)

        // Act — push failure must not propagate
        assertDoesNotThrow { accountDeletionService.deleteAccount(userId) }

        // Assert — user is still deleted
        verify(exactly = 1) { userRepository.delete(user) }
    }

    @Test
    fun `deleteAccount should record audit log before deleting user row`() {
        // Arrange
        val userId = 36L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act
        accountDeletionService.deleteAccount(userId)

        // Assert
        verify(exactly = 1) { auditLogService.logAccountDeletion(userId, user.email, null) }
    }

    @Test
    fun `deleteAccount should skip Firebase deletion when user has no googleId`() {
        // Arrange
        val userId = 37L
        val user = createUser(id = userId, googleId = null)
        stubDeleteAccount(userId, user)

        // Act — if FirebaseAuth were called on a null googleId this would throw
        assertDoesNotThrow { accountDeletionService.deleteAccount(userId) }
    }

    @Test
    fun `deleteAccount should continue when Firebase deletion throws`() {
        // Arrange — user has a googleId so Firebase deletion is attempted, but Firebase is not
        // available in unit tests. The service wraps the call in try/catch so it must not throw.
        val userId = 38L
        // Providing a googleId triggers the Firebase block; FirebaseAuth.getInstance() will throw
        // an IllegalStateException (no app initialized) which is caught internally.
        val user = createUser(id = userId, googleId = "firebase-uid-123")
        stubDeleteAccount(userId, user)

        // Act
        assertDoesNotThrow { accountDeletionService.deleteAccount(userId) }

        // Assert — despite Firebase failure, user is deleted
        verify(exactly = 1) { userRepository.delete(user) }
    }
}
