package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AuthUser
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SubscriptionController::class, SubscriptionAdminController::class)
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class SubscriptionControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var subscriptionService: SubscriptionService

    @MockitoBean
    private lateinit var featureAccessService: FeatureAccessService

    // Unique ids per test: the per-user sync rate-limit bucket lives in a shared singleton.
    private fun userAuth(id: Long) = UsernamePasswordAuthenticationToken(AuthUser(id), null, emptyList())

    private fun stubFeatureAccess(userId: Long, premium: Boolean) {
        `when`(featureAccessService.getFeatureAccess(userId)).thenReturn(
            FeatureAccessResponse(
                featureFlags = ClientFeatureFlags(pushNotificationsEnabled = true),
                userAccess = UserFeatureAccess(hasPremiumAccess = premium),
            )
        )
    }

    // ── POST /api/v1/subscriptions/sync ────────────────────────────────────────

    @Test
    fun `sync returns fresh feature access`() {
        `when`(subscriptionService.syncFromRevenueCat(9001L)).thenReturn(SyncOutcome.UPDATED)
        stubFeatureAccess(9001L, premium = true)

        mockMvc.perform(post("/api/v1/subscriptions/sync").with(authentication(userAuth(9001L))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.userAccess.hasPremiumAccess").value(true))
            .andExpect(jsonPath("$.data.featureFlags.pushNotificationsEnabled").value(true))
    }

    @Test
    fun `sync is rate limited per user`() {
        `when`(subscriptionService.syncFromRevenueCat(9002L)).thenReturn(SyncOutcome.UNCHANGED)
        stubFeatureAccess(9002L, premium = false)

        repeat(2) {
            mockMvc.perform(post("/api/v1/subscriptions/sync").with(authentication(userAuth(9002L))))
                .andExpect(status().isOk)
        }
        mockMvc.perform(post("/api/v1/subscriptions/sync").with(authentication(userAuth(9002L))))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    @Test
    fun `sync failure returns 500 without leaking details`() {
        `when`(subscriptionService.syncFromRevenueCat(9003L)).thenThrow(RuntimeException("db password=x"))

        mockMvc.perform(post("/api/v1/subscriptions/sync").with(authentication(userAuth(9003L))))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
    }

    // ── POST /admin/subscriptions/reconcile ────────────────────────────────────

    private val adminAuth = UsernamePasswordAuthenticationToken(
        "ali-cli", null, listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
    )

    @Test
    fun `admin reconcile with explicit user ids`() {
        `when`(subscriptionService.reconcile(listOf(1L, 2L)))
            .thenReturn(ReconcileReport(checked = 2, outcomes = mapOf(SyncOutcome.UPDATED to 2), expired = 0))

        mockMvc.perform(post("/admin/subscriptions/reconcile").param("userIds", "1,2").with(authentication(adminAuth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.checked").value(2))
            .andExpect(jsonPath("$.data.outcomes.UPDATED").value(2))
    }

    @Test
    fun `admin reconcile rejects unknown scope`() {
        mockMvc.perform(post("/admin/subscriptions/reconcile").param("scope", "everyone").with(authentication(adminAuth)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `admin reconcile is forbidden for regular users`() {
        mockMvc.perform(post("/admin/subscriptions/reconcile").with(authentication(userAuth(9004L))))
            .andExpect(status().isForbidden)
    }
}
