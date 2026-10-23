package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SettingsController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class SettingsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var userSettingsService: UserSettingsService

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(1L), null, emptyList())

    @Test
    fun `PATCH settings should return 400 when reminder time is not HH-MM`() {
        mockMvc.perform(
            patch("/api/v1/settings")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createSettings(dailyReminderTime = "25:99")))
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(userSettingsService)
    }

    @Test
    fun `PATCH settings should return 400 when notification frequency is unknown`() {
        mockMvc.perform(
            patch("/api/v1/settings")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createSettings(notificationFrequency = "HOURLY")))
        )
            .andExpect(status().isBadRequest)
    }

    // --- factories ---

    private fun createSettings(
        dailyReminderTime: String = "18:00",
        notificationFrequency: String = "DAILY",
    ) = SettingsDto(
        languageCode = "en",
        themeMode = "AUTO",
        notificationsEnabled = true,
        dailyReminderTime = dailyReminderTime,
        notificationFrequency = notificationFrequency,
    )
}
