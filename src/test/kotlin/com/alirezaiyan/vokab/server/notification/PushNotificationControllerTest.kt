package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(PushNotificationController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class PushNotificationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var handler: NotificationControllerHandler

    private val mockUser = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0
    )

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    private val testToken = "valid-push-token-12345"
    private val deviceId = "device-123"

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value) as T

    // ── POST /api/v1/notifications/register-token ────────────────────────────────

    @Test
    fun `should register push token successfully`() {
        val request = RegisterPushTokenRequest(
            token = testToken,
            platform = Platform.ANDROID,
            deviceId = deviceId
        )

        `when`(handler.registerToken(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, message = "Push token registered successfully"))
        )

        mockMvc.post("/api/v1/notifications/register-token") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).registerToken(eqArg(mockUser.requireId()), anyArg())
    }

    @Test
    fun `should validate required token field`() {
        val invalidRequest = """
            {
                "platform": "ANDROID",
                "deviceId": "device-123"
            }
        """.trimIndent()

        mockMvc.post("/api/v1/notifications/register-token") {
            contentType = MediaType.APPLICATION_JSON
            content = invalidRequest
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `should accept empty deviceId`() {
        val request = RegisterPushTokenRequest(
            token = testToken,
            platform = Platform.IOS
        )

        `when`(handler.registerToken(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, message = "Push token registered successfully"))
        )

        mockMvc.post("/api/v1/notifications/register-token") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `should handle handler exception on token registration`() {
        val request = RegisterPushTokenRequest(
            token = testToken,
            platform = Platform.ANDROID
        )

        // The real handler catches exceptions internally and returns 400.
        // The mock must simulate this behavior by returning a bad request response.
        `when`(handler.registerToken(eqArg(mockUser.requireId()), anyArg())).thenReturn(
            ResponseEntity.badRequest().body(ApiResponse(success = false, message = "Operation failed: Database error"))
        )

        mockMvc.post("/api/v1/notifications/register-token") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }.andExpect {
            jsonPath("$.success") { value(false) }
        }
    }

    // ── DELETE /api/v1/notifications/token/{token} ────────────────────────────────

    @Test
    fun `should deactivate specific token successfully`() {
        `when`(handler.deactivateToken(eqArg(mockUser.requireId()), eqArg(testToken))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, message = "Token deactivated successfully"))
        )

        mockMvc.delete("/api/v1/notifications/token/$testToken") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).deactivateToken(eqArg(mockUser.requireId()), eqArg(testToken))
    }

    @Test
    fun `should handle deactivate token exception`() {
        `when`(handler.deactivateToken(eqArg(mockUser.requireId()), anyArg())).thenReturn(
            ResponseEntity.badRequest().body(ApiResponse(success = false, message = "Operation failed: Token not found"))
        )

        mockMvc.delete("/api/v1/notifications/token/$testToken") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }.andExpect {
            jsonPath("$.success") { value(false) }
        }
    }

    @Test
    fun `should deactivate token with special characters in URL`() {
        val specialToken = "token-with-special-chars_123"
        `when`(handler.deactivateToken(eqArg(mockUser.requireId()), eqArg(specialToken))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, message = "Token deactivated successfully"))
        )

        mockMvc.delete("/api/v1/notifications/token/$specialToken") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).deactivateToken(eqArg(mockUser.requireId()), eqArg(specialToken))
    }

    // ── DELETE /api/v1/notifications/tokens ────────────────────────────────────────

    @Test
    fun `should deactivate all user tokens successfully`() {
        `when`(handler.deactivateAllTokens(eqArg(mockUser.requireId()))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, message = "All tokens deactivated successfully"))
        )

        mockMvc.delete("/api/v1/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).deactivateAllTokens(eqArg(mockUser.requireId()))
    }

    @Test
    fun `should handle deactivate all tokens exception`() {
        `when`(handler.deactivateAllTokens(eqArg(mockUser.requireId()))).thenReturn(
            ResponseEntity.badRequest().body(ApiResponse(success = false, message = "Operation failed: Database error"))
        )

        mockMvc.delete("/api/v1/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    // ── POST /api/v1/notifications/send ────────────────────────────────────────────

    @Test
    fun `should send notification with title and body`() {
        val request = SendNotificationRequest(
            title = "Test Title",
            body = "Test Body"
        )

        `when`(handler.sendNotification(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = emptyList<NotificationResponse>()))
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).sendNotification(eqArg(mockUser.requireId()), anyArg())
    }

    @Test
    fun `should send notification with data payload`() {
        val request = SendNotificationRequest(
            title = "Streak Reminder",
            body = "Keep your streak alive!",
            data = mapOf(
                "type" to "streak_reminder",
                "streak_count" to "5"
            )
        )

        `when`(handler.sendNotification(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = emptyList<NotificationResponse>()))
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `should send notification with image URL`() {
        val request = SendNotificationRequest(
            title = "New Word",
            body = "Check out this new word!",
            imageUrl = "https://example.com/image.jpg"
        )

        `when`(handler.sendNotification(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = emptyList<NotificationResponse>()))
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `should validate required title field`() {
        val invalidRequest = """
            {
                "body": "Missing title"
            }
        """.trimIndent()

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = invalidRequest
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `should validate required body field`() {
        val invalidRequest = """
            {
                "title": "Missing body"
            }
        """.trimIndent()

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = invalidRequest
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `should return notification responses from handler`() {
        val request = SendNotificationRequest(
            title = "Test",
            body = "Test Body"
        )

        val mockResponses = listOf(
            NotificationResponse(success = true, messageId = "msg-1"),
            NotificationResponse(success = false, messageId = null)
        )

        `when`(handler.sendNotification(eqArg(mockUser.requireId()), eqArg(request))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = mockResponses))
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `should handle send notification exception`() {
        val request = SendNotificationRequest(
            title = "Test",
            body = "Test Body"
        )

        `when`(handler.sendNotification(eqArg(mockUser.requireId()), anyArg())).thenReturn(
            ResponseEntity.badRequest().body(ApiResponse(success = false, message = "Operation failed: FCM unavailable"))
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    // ── GET /api/v1/notifications/tokens ───────────────────────────────────────────

    @Test
    fun `should get user token count successfully`() {
        `when`(handler.getUserTokens(eqArg(mockUser.requireId()))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = 3, message = "3 active tokens"))
        )

        mockMvc.get("/api/v1/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }

        verify(handler).getUserTokens(eqArg(mockUser.requireId()))
    }

    @Test
    fun `should return token count as integer`() {
        `when`(handler.getUserTokens(eqArg(mockUser.requireId()))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true, data = 2, message = "2 active tokens"))
        )

        mockMvc.get("/api/v1/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `should handle get tokens exception`() {
        `when`(handler.getUserTokens(eqArg(mockUser.requireId()))).thenReturn(
            ResponseEntity.badRequest().body(ApiResponse(success = false, message = "Operation failed: Database error"))
        )

        mockMvc.get("/api/v1/notifications/tokens") {
            contentType = MediaType.APPLICATION_JSON
            with(authentication(auth))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────────

    @Test
    fun `should require authentication for register token`() {
        val request = RegisterPushTokenRequest(
            token = testToken,
            platform = Platform.ANDROID
        )

        mockMvc.post("/api/v1/notifications/register-token") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `should require authentication for send notification`() {
        val request = SendNotificationRequest(
            title = "Test",
            body = "Test Body"
        )

        mockMvc.post("/api/v1/notifications/send") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `should require authentication for get tokens`() {
        mockMvc.get("/api/v1/notifications/tokens").andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `should require authentication for deactivate token`() {
        mockMvc.delete("/api/v1/notifications/token/$testToken").andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `should require authentication for deactivate all tokens`() {
        mockMvc.delete("/api/v1/notifications/tokens").andExpect {
            status { isForbidden() }
        }
    }

    // ── POST /api/v1/notifications/{logId}/opened ────────────────────────────────

    @Test
    fun `should record notification open`() {
        `when`(handler.markOpened(eqArg(mockUser.requireId()), eqArg(42L))).thenReturn(
            ResponseEntity.ok(ApiResponse(success = true))
        )

        mockMvc.post("/api/v1/notifications/42/opened") {
            with(authentication(auth))
        }.andExpect {
            status { isOk() }
            jsonPath("$.success") { value(true) }
        }

        verify(handler).markOpened(eqArg(mockUser.requireId()), eqArg(42L))
    }

    @Test
    fun `should return 404 when notification log is unknown or not the caller's`() {
        `when`(handler.markOpened(eqArg(mockUser.requireId()), eqArg(42L))).thenThrow(NoSuchElementException("Notification not found"))

        mockMvc.post("/api/v1/notifications/42/opened") {
            with(authentication(auth))
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `should require authentication for notification open`() {
        mockMvc.post("/api/v1/notifications/42/opened").andExpect {
            status { isForbidden() }
        }
    }
}
