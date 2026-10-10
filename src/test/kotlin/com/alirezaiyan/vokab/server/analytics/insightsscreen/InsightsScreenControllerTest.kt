package com.alirezaiyan.vokab.server.analytics.insightsscreen

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.ZoneId
import java.time.ZoneOffset

@WebMvcTest(InsightsScreenController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class InsightsScreenControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var service: InsightsScreenService

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(1L), null, emptyList())
    private val response = snapshot().let { s ->
        val sections = SectionBuilders.build(s)
        InsightsScreenResponse(
            generatedAt = s.now.toInstant().toString(),
            totalReviews = s.totalReviewsAllTime,
            hero = HeroBuilder.build(s),
            coach = emptyList(),
            sections = sections.sections,
            locked = sections.locked,
        )
    }

    @Test
    fun `GET insights-screen passes the parsed zone`() {
        `when`(service.build(1L, ZoneId.of("Asia/Tokyo"))).thenReturn(response)

        mockMvc.perform(get("/api/v1/analytics/insights-screen").param("tz", "Asia/Tokyo").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.hero.week.length()").value(7))
    }

    @Test
    fun `invalid or missing tz falls back to UTC`() {
        `when`(service.build(1L, ZoneOffset.UTC)).thenReturn(response)

        mockMvc.perform(get("/api/v1/analytics/insights-screen").param("tz", "Mars/Olympus").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.hero.week.length()").value(7))
        mockMvc.perform(get("/api/v1/analytics/insights-screen").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.hero.week.length()").value(7))
    }
}
