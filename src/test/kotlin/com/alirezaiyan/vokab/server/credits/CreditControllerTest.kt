package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.subscription.AccessLevel
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
import java.time.Instant

@WebMvcTest(CreditController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class, CreditProperties::class)
class CreditControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var creditService: CreditService

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(4), null, emptyList())

    @Test
    fun `GET credits returns the balance with costs and per-tier allowances`() {
        `when`(creditService.balance(4)).thenReturn(
            CreditBalance(
                tier = AccessLevel.FREE,
                allowanceRemaining = 2,
                monthlyAllowance = 5,
                bonusBalance = 15,
                periodEndsAt = Instant.parse("2026-11-09T10:00:00Z"),
            )
        )

        mockMvc.perform(get("/api/v1/credits").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.balance").value(17))
            .andExpect(jsonPath("$.data.allowanceRemaining").value(2))
            .andExpect(jsonPath("$.data.monthlyAllowance").value(5))
            .andExpect(jsonPath("$.data.bonusBalance").value(15))
            .andExpect(jsonPath("$.data.periodEndsAt").value("2026-11-09T10:00:00Z"))
            .andExpect(jsonPath("$.data.tier").value("FREE"))
            .andExpect(jsonPath("$.data.costs.PHOTO_EXTRACTION").value(2))
            .andExpect(jsonPath("$.data.costs.AI_SUGGESTION").value(2))
            .andExpect(jsonPath("$.data.monthlyAllowances.PREMIUM").value(300))
    }
}
