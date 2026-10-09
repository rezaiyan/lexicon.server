package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.subscription.AccessLevel
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The credit economy, tunable per environment without a client release: the client reads costs
 * and allowances from `GET /api/v1/credits`. An action costing 0 is free and leaves no ledger row.
 */
@ConfigurationProperties(prefix = "app.credits")
data class CreditProperties(
    /** One-time bonus on wallet creation (new and existing users alike). Never expires. */
    var signupBonus: Int = 15,
    var freeMonthlyAllowance: Int = 5,
    var trialMonthlyAllowance: Int = 30,
    var premiumMonthlyAllowance: Int = 300,
    var costs: Map<CreditAction, Int> = mapOf(
        CreditAction.PHOTO_EXTRACTION to 3,
        CreditAction.AI_SUGGESTION to 1,
        CreditAction.TEXT_TRANSLATION to 1,
    ),
) {
    fun allowanceFor(level: AccessLevel): Int = when (level) {
        AccessLevel.FREE -> freeMonthlyAllowance
        AccessLevel.TRIAL -> trialMonthlyAllowance
        AccessLevel.PREMIUM -> premiumMonthlyAllowance
    }

    /** Unconfigured actions are free rather than unusable. */
    fun costOf(action: CreditAction): Int = costs[action] ?: 0
}

/** Something that spends credits. Names are part of the API contract (cost map keys). */
enum class CreditAction { PHOTO_EXTRACTION, AI_SUGGESTION, TEXT_TRANSLATION }
