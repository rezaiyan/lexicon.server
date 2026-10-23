package com.alirezaiyan.vokab.server.subscription

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RevenueCatEventTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `reads pause, grace period and expiration reason from RevenueCat's snake_case payload`() {
        val webhook = mapper.readValue<RevenueCatWebhookEvent>(
            """
            {"api_version":"1.0","event":{
              "id":"e1","type":"EXPIRATION","app_user_id":"1",
              "expiration_reason":"SUBSCRIPTION_PAUSED",
              "grace_period_expiration_at_ms":1700000000000,
              "auto_resume_at_ms":1800000000000,
              "unknown_field":true
            }}
            """.trimIndent()
        )

        assertEquals("SUBSCRIPTION_PAUSED", webhook.event.expirationReason)
        assertEquals(1_700_000_000_000L, webhook.event.gracePeriodExpirationAtMs)
        assertEquals(1_800_000_000_000L, webhook.event.autoResumeAtMs)
    }
}
