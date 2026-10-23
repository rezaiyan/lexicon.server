package com.alirezaiyan.vokab.server.analytics.insightsscreen

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class CoachEngine(private val rules: List<CoachRule>) {

    fun cardsFor(snapshot: LearnerSnapshot): List<CoachCardDto> = rules
        .sortedByDescending { it.priority }
        .mapNotNull { rule ->
            evaluateSafely(rule, snapshot)
        }
        .take(MAX_CARDS)

    /** A broken rule is skipped; Errors (OOM, StackOverflow) still propagate. */
    @Suppress("TooGenericExceptionCaught")
    private fun evaluateSafely(rule: CoachRule, snapshot: LearnerSnapshot): CoachCardDto? = try {
        rule.evaluate(snapshot)
    } catch (e: Exception) {
        logger.warn(e) { "Coach rule ${rule.type} failed; skipping" }
        null
    }

    companion object {
        const val MAX_CARDS = 3
    }
}
