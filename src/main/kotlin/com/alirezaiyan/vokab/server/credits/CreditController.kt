package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.subscription.AccessLevel
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/credits")
class CreditController(
    private val creditService: CreditService,
    private val properties: CreditProperties,
) {
    /** The caller's balance plus the price list, so the app never hardcodes costs or allowances. */
    @GetMapping
    fun balance(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ApiResponse<CreditBalanceResponse>> {
        val balance = creditService.balance(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = balance.toResponse(properties)))
    }
}

/**
 * Map keys are [CreditAction] and [AccessLevel] names. Clients must ignore keys they don't know,
 * so actions and tiers can be added without breaking older apps.
 */
data class CreditBalanceResponse(
    /** What can be spent right now: [allowanceRemaining] + [bonusBalance]. */
    val balance: Int,
    val allowanceRemaining: Int,
    /** The current tier's allowance, restored in full at [periodEndsAt]. */
    val monthlyAllowance: Int,
    /** Signup bonus and grants; never expires. */
    val bonusBalance: Int,
    /** ISO-8601 instant the allowance resets. */
    val periodEndsAt: String,
    /** [AccessLevel] name the allowance is granted for. */
    val tier: String,
    val costs: Map<String, Int>,
    /** Allowance per tier, e.g. for the paywall to show what premium includes. */
    val monthlyAllowances: Map<String, Int>,
)

internal fun CreditBalance.toResponse(properties: CreditProperties) = CreditBalanceResponse(
    balance = balance,
    allowanceRemaining = allowanceRemaining,
    monthlyAllowance = monthlyAllowance,
    bonusBalance = bonusBalance,
    periodEndsAt = periodEndsAt.toString(),
    tier = tier.name,
    costs = CreditAction.entries.associate { it.name to properties.costOf(it) },
    monthlyAllowances = AccessLevel.entries.associate { it.name to properties.allowanceFor(it) },
)
