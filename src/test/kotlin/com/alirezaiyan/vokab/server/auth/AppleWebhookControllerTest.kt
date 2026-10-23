package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(AppleWebhookController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class AppleWebhookControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var appleNotificationService: AppleNotificationService

    private val validJwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6InRlc3Qta2V5In0.eyJzdWIiOiJhcHBsZS11c2VyLWlkIn0.signature"
    private val malformedJwt = "not-a-valid-jwt"

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    // ── POST /api/v1/webhooks/apple: successful processing ────────────────────────

    @Test
    fun `should return 200 OK with OK response when notification processed successfully`() {
        val notification = AppleServerNotification(payload = validJwt)
        `when`(appleNotificationService.processNotification(validJwt)).thenReturn(true)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("OK") }
        }

        verify(appleNotificationService).processNotification(validJwt)
    }

    @Test
    fun `should call service with correct payload`() {
        val notification = AppleServerNotification(payload = validJwt)
        `when`(appleNotificationService.processNotification(validJwt)).thenReturn(true)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }

        verify(appleNotificationService).processNotification(validJwt)
    }

    // ── POST /api/v1/webhooks/apple: service returns false ────────────────────────

    @Test
    fun `should return 200 FAILED when service returns false`() {
        val notification = AppleServerNotification(payload = malformedJwt)
        `when`(appleNotificationService.processNotification(malformedJwt)).thenReturn(false)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("FAILED") }
        }
    }

    // ── POST /api/v1/webhooks/apple: service throws exception ──────────────────────

    @Test
    fun `should return 200 ERROR when service throws exception`() {
        val notification = AppleServerNotification(payload = "bad-payload")
        `when`(appleNotificationService.processNotification("bad-payload"))
            .thenThrow(RuntimeException("JWT verification failed"))

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("ERROR") }
        }
    }

    @Test
    fun `should catch and handle NullPointerException gracefully`() {
        val notification = AppleServerNotification(payload = "null-causing-payload")
        `when`(appleNotificationService.processNotification("null-causing-payload"))
            .thenThrow(NullPointerException("Unexpected null value"))

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("ERROR") }
        }
    }

    @Test
    fun `should catch and handle IllegalArgumentException gracefully`() {
        val notification = AppleServerNotification(payload = "invalid-payload")
        `when`(appleNotificationService.processNotification("invalid-payload"))
            .thenThrow(IllegalArgumentException("Invalid payload format"))

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("ERROR") }
        }
    }

    // ── GET /api/v1/webhooks/apple: health check ──────────────────────────────────

    @Test
    fun `should return 200 with health check data on GET`() {
        mockMvc.get("/api/v1/webhooks/apple").andExpect {
            status { isOk() }
        }.andExpect {
            jsonPath("$.status") { value("active") }
            jsonPath("$.service") { value("Apple Server-to-Server Notifications") }
            jsonPath("$.message") { value("Webhook endpoint is ready to receive notifications") }
        }
    }

    @Test
    fun `health check should not call service`() {
        mockMvc.get("/api/v1/webhooks/apple")

        verify(appleNotificationService, never()).processNotification(anyArg())
    }

    // ── POST /api/v1/webhooks/apple: edge cases ──────────────────────────────────

    @Test
    fun `should handle empty payload gracefully`() {
        val notification = AppleServerNotification(payload = "")
        `when`(appleNotificationService.processNotification("")).thenReturn(false)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("FAILED") }
        }
    }

    @Test
    fun `should handle very long payload`() {
        val longPayload = "a".repeat(10000)
        val notification = AppleServerNotification(payload = longPayload)
        `when`(appleNotificationService.processNotification(longPayload)).thenReturn(false)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification)
        }.andExpect {
            status { isOk() }
        }.andExpect {
            content { string("FAILED") }
        }
    }

    @Test
    fun `should handle null payload gracefully`() {
        // Send a request without the payload field — expects 400 from JSON deserialization
        val jsonBody = "{}"

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonBody
        }.andExpect {
            status { isBadRequest() }
        }
    }

    // ── POST /api/v1/webhooks/apple: concurrent requests ────────────────────────

    @Test
    fun `should handle multiple concurrent webhooks independently`() {
        val notification1 = AppleServerNotification(payload = "jwt-1")
        val notification2 = AppleServerNotification(payload = "jwt-2")

        `when`(appleNotificationService.processNotification("jwt-1")).thenReturn(true)
        `when`(appleNotificationService.processNotification("jwt-2")).thenReturn(false)

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification1)
        }.andExpect {
            status { isOk() }
            content { string("OK") }
        }

        mockMvc.post("/api/v1/webhooks/apple") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(notification2)
        }.andExpect {
            status { isOk() }
            content { string("FAILED") }
        }

        verify(appleNotificationService).processNotification("jwt-1")
        verify(appleNotificationService).processNotification("jwt-2")
    }
}
