package com.alirezaiyan.vokab.server.analytics.insightsscreen

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class CoachEngine(private val rules: List<CoachRule>) {

    fun cardsFor(snapshot: LearnerSnapshot): List<CoachCardDto> = rules
        .sortedByDescending { it.priority }
        .mapNotNull { rule ->
            runCatching { rule.evaluate(snapshot) }
                .onFailure { logger.warn(it) { "Coach rule ${rule.type} failed; skipping" } }
                .getOrNull()
        }
        .take(MAX_CARDS)

    companion object {
        const val MAX_CARDS = 3
    }
}
