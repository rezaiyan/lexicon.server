package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.AppProperties
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Passive: derived from real traffic via [AiCallTracker]. Not part of readiness — an AI outage
 * degrades AI features (502s) but must not take the whole API out of service.
 */
@Component
class OpenRouterHealthIndicator(
    private val tracker: AiCallTracker,
    private val appProperties: AppProperties,
) : HealthIndicator {

    override fun health(): Health {
        if (appProperties.openrouter.apiKey.isBlank()) {
            return Health.unknown().withDetail("reason", "OPENROUTER_API_KEY not set").build()
        }
        val builder = if (tracker.failing) Health.down() else Health.up()
        tracker.lastSuccessAt?.let { builder.withDetail("lastSuccessAt", it.toString()) }
        tracker.lastFailureAt?.let { builder.withDetail("lastFailureAt", it.toString()) }
        return builder.build()
    }
}
