package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(SupportController::class, SupportAdminController::class, TelegramWebhookController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class, RateLimitConfig::class)
@TestPropertySource(properties = ["app.notifications.admin.telegram.webhook-secret=s3cret"])
class SupportControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var supportService: SupportService

    private val user = UsernamePasswordAuthenticationToken(AuthUser(USER_ID), null, emptyList())
    private val admin = UsernamePasswordAuthenticationToken(
        "ali-cli", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    @Test
    fun `send passes platform headers to the service`() {
        val dto = SupportMessageDto(1, SupportSender.USER, "hi", "c1", 0)
        `when`(
            supportService.sendUserMessage(USER_ID, SendSupportMessageRequest("hi", "c1"), SupportClientInfo("iOS", "1.46.0"))
        ).thenReturn(dto)

        mockMvc.post("/api/v1/support/messages") {
            with(authentication(user))
            header("X-Platform", "iOS")
            header("X-App-Version", "1.46.0")
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"hi","clientMessageId":"c1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.body") { value("hi") }
        }
    }

    @Test
    fun `blank message is rejected`() {
        mockMvc.post("/api/v1/support/messages") {
            with(authentication(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"   "}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `messages endpoint returns the thread`() {
        `when`(supportService.getMessages(USER_ID, 5)).thenReturn(SupportMessagesDto(emptyList(), 2))

        mockMvc.get("/api/v1/support/messages?after=5") { with(authentication(user)) }
            .andExpect { jsonPath("$.data.unreadCount") { value(2) } }
    }

    @Test
    fun `admin support endpoints reject regular users`() {
        mockMvc.get("/admin/support/threads") { with(authentication(user)) }
            .andExpect { status { isForbidden() } }
        mockMvc.get("/admin/support/threads") { with(authentication(admin)) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `webhook without the secret is rejected`() {
        mockMvc.post("/api/v1/webhooks/telegram") {
            header("X-Telegram-Bot-Api-Secret-Token", "wrong")
            contentType = MediaType.APPLICATION_JSON
            content = """{"update_id":1}"""
        }.andExpect { status { isUnauthorized() } }

        verify(supportService, never()).handleTelegramUpdate(anyArg())
    }

    @Test
    fun `webhook with the secret hands the update to the service`() {
        mockMvc.post("/api/v1/webhooks/telegram") {
            header("X-Telegram-Bot-Api-Secret-Token", "s3cret")
            contentType = MediaType.APPLICATION_JSON
            content = """{"update_id":1,"message":{"message_id":9,"chat":{"id":42},"text":"ok",""" +
                """"reply_to_message":{"message_id":3},"unknown_field":true}}"""
        }.andExpect { status { isOk() } }

        verify(supportService).handleTelegramUpdate(
            TelegramUpdate(1, TelegramMessage(9, TelegramChat(42), null, "ok", TelegramMessage(3)))
        )
    }

    private companion object {
        const val USER_ID = 77L
    }
}
