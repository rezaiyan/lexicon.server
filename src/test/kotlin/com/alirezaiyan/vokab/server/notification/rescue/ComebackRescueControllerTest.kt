package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(ComebackRescueController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class ComebackRescueControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var service: ComebackRescueService

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(1L), null, emptyList())

    @Test
    fun `completes the user's rescue`() {
        `when`(service.complete(1L, 42L)).thenReturn(true)

        mockMvc.post("/api/v1/notifications/rescue/42/complete") { with(authentication(auth)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
            }
    }

    @Test
    fun `unknown or foreign rescue is not found`() {
        `when`(service.complete(1L, 43L)).thenReturn(false)

        mockMvc.post("/api/v1/notifications/rescue/43/complete") { with(authentication(auth)) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `requires authentication`() {
        mockMvc.post("/api/v1/notifications/rescue/42/complete")
            .andExpect { status { is4xxClientError() } }
    }
}
