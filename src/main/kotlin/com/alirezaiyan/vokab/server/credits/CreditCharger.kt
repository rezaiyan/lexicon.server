package com.alirezaiyan.vokab.server.credits

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Pays for a piece of work with credits: spend first (so concurrent requests can't overdraw),
 * run the work outside any transaction (AI calls take seconds; the wallet lock must not), and
 * refund when the work fails or [refundIf] says the user got nothing.
 */
@Component
class CreditCharger(private val creditService: CreditService) {

    fun <T> charge(
        userId: Long,
        action: CreditAction,
        refundIf: (T) -> Boolean = { false },
        work: () -> T,
    ): T {
        val spend = creditService.spend(userId, action)
        val result = runCatching(work)
            .onFailure { refundQuietly(spend) }
            .getOrThrow()
        if (refundIf(result)) refundQuietly(spend)
        return result
    }

    /** A failed refund must not mask the work's own outcome; it's logged for follow-up. */
    private fun refundQuietly(spend: CreditSpend?) {
        if (spend == null) return
        runCatching { creditService.refund(spend) }
            .onSuccess { logger.info { "Refunded ${spend.cost} credits to userId=${spend.userId}" } }
            .onFailure { logger.error(it) { "Refund of spend ${spend.transactionId} for userId=${spend.userId} failed" } }
    }
}
