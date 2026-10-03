package com.alirezaiyan.vokab.server.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuditLogServiceTest {

    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var auditLogService: AuditLogService

    @BeforeEach
    fun setUp() {
        auditLogRepository = mockk()
        auditLogService = AuditLogService(auditLogRepository)
    }

    @Test
    fun `logLogin should save audit log with LOGIN event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logLogin(
            userId = 1L,
            email = "user@example.com",
            provider = "google",
            ipAddress = "127.0.0.1",
            userAgent = "Mozilla/5.0"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.LOGIN && it.userId == 1L })
        }
    }

    @Test
    fun `logRefresh should save audit log with TOKEN_REFRESH event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logRefresh(
            userId = 2L,
            email = "user@example.com",
            familyId = "family-abc-123",
            ipAddress = "192.168.0.1",
            userAgent = null
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.TOKEN_REFRESH && it.userId == 2L })
        }
    }

    @Test
    fun `logLogout should save audit log with LOGOUT event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logLogout(
            userId = 3L,
            email = "user@example.com",
            refreshToken = "abcdefghijklmnop",
            ipAddress = "10.0.0.1"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.LOGOUT && it.userId == 3L })
        }
    }

    @Test
    fun `logLogoutAll should save audit log with LOGOUT_ALL event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logLogoutAll(
            userId = 4L,
            email = "user@example.com",
            ipAddress = "10.0.0.2"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.LOGOUT_ALL && it.userId == 4L })
        }
    }

    @Test
    fun `logTokenRevocation should save audit log with TOKEN_REVOCATION event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logTokenRevocation(
            userId = 5L,
            familyId = "family-xyz-456",
            reason = "reuse_detected",
            ipAddress = "172.16.0.1"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.TOKEN_REVOCATION && it.userId == 5L })
        }
    }

    @Test
    fun `logTokenReuse should save audit log with TOKEN_REUSE event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logTokenReuse(
            userId = 6L,
            familyId = "family-dup-789",
            ipAddress = "203.0.113.1"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.TOKEN_REUSE && it.userId == 6L })
        }
    }

    @Test
    fun `logAccountDeletion should save audit log with ACCOUNT_DELETION event type`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logAccountDeletion(
            userId = 7L,
            email = "deleted@example.com",
            ipAddress = "198.51.100.1"
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.eventType == AuditEventType.ACCOUNT_DELETION && it.userId == 7L })
        }
    }

    @Test
    fun `logLogin should record email in the saved audit log`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logLogin(
            userId = 1L,
            email = "user@example.com",
            provider = "google",
            ipAddress = null,
            userAgent = null
        )

        // Assert
        verify(exactly = 1) {
            auditLogRepository.save(match { it.email == "user@example.com" })
        }
    }

    @Test
    fun `save should not throw when repository throws`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } throws RuntimeException("DB connection failed")

        // Act & Assert — the internal save catches all exceptions
        assertDoesNotThrow {
            auditLogService.logLogin(
                userId = 99L,
                email = "user@example.com",
                provider = "apple",
                ipAddress = null,
                userAgent = null
            )
        }
    }

    @Test
    fun `logTokenRevocation should not have email in saved audit log`() {
        // Arrange
        every { auditLogRepository.save(any<AuditLog>()) } returns mockk()

        // Act
        auditLogService.logTokenRevocation(
            userId = 5L,
            familyId = "family-xyz",
            reason = "security",
            ipAddress = null
        )

        // Assert — logTokenRevocation does not pass email to save()
        verify(exactly = 1) {
            auditLogRepository.save(match { it.email == null })
        }
    }
}
