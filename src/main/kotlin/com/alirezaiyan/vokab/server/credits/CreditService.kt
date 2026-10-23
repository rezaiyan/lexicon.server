package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.InsufficientCreditsException
import com.alirezaiyan.vokab.server.subscription.AccessLevel
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

/**
 * AI credit balances. Every operation locks the user's wallet row, brings it up to date, then
 * applies its change together with a ledger row, so balances never go negative and concurrent
 * requests of one user can't spend the same credit twice.
 *
 * Periods are settled lazily on access rather than by a scheduled job:
 * - period over → a new one starts with the current tier's allowance (no rollover);
 * - upgrade (bigger allowance) → the period is kept and the allowance is topped up to the new
 *   tier's amount minus what was already spent from the allowance this period, so a period never
 *   yields more than the highest tier's allowance however often the tier flips;
 * - downgrade → the remaining allowance is clamped to the new tier's, the period is kept.
 */
@Service
class CreditService(
    private val walletRepository: CreditWalletRepository,
    private val transactionRepository: CreditTransactionRepository,
    private val properties: CreditProperties,
    private val featureAccessService: FeatureAccessService,
    private val clock: Clock,
) {

    @Transactional
    fun balance(userId: Long): CreditBalance = currentWallet(userId).toBalance()

    /**
     * Throws [InsufficientCreditsException] when the balance is below [action]'s cost; spends nothing.
     * For paid preparation (e.g. screening a request) that should not run for a user who can't pay.
     */
    @Transactional
    fun requireAffordable(userId: Long, action: CreditAction) {
        val cost = properties.costOf(action)
        if (cost <= 0) return
        val available = currentWallet(userId).balance
        if (available < cost) throw InsufficientCreditsException(action.name, required = cost, available = available)
    }

    /**
     * Takes [action]'s cost, allowance first (it expires), then bonus.
     * Returns null for a free action. Throws [InsufficientCreditsException] when the balance is short.
     */
    @Transactional
    fun spend(userId: Long, action: CreditAction): CreditSpend? {
        val cost = properties.costOf(action)
        if (cost <= 0) return null

        val wallet = currentWallet(userId)
        if (wallet.balance < cost) {
            logger.info { "userId=$userId lacks credits for $action: cost=$cost, balance=${wallet.balance}" }
            throw InsufficientCreditsException(action.name, required = cost, available = wallet.balance)
        }
        val fromAllowance = minOf(cost, wallet.allowanceRemaining)
        val fromBonus = cost - fromAllowance
        wallet.allowanceRemaining -= fromAllowance
        wallet.bonusBalance -= fromBonus
        wallet.updatedAt = now()

        val entry = record(
            userId, CreditTransactionType.SPEND,
            allowanceDelta = -fromAllowance, bonusDelta = -fromBonus, action = action,
        )
        return CreditSpend(transactionId = requireNotNull(entry.id), userId = userId, cost = cost)
    }

    /**
     * Gives a spend back, to the buckets it came from. Idempotent. Allowance spent in a period that
     * has since ended is not restored (it would have expired anyway), and the allowance never
     * exceeds the tier's monthly amount.
     */
    @Transactional
    fun refund(spend: CreditSpend) {
        val entry = transactionRepository.findById(spend.transactionId).orElse(null)
        if (entry == null || entry.type != CreditTransactionType.SPEND || entry.userId != spend.userId) {
            logger.warn { "Ignoring refund of unknown spend ${spend.transactionId} for userId=${spend.userId}" }
            return
        }
        val wallet = currentWallet(spend.userId)
        // Checked under the wallet lock, so two refunds of one spend can't both pass
        if (transactionRepository.existsByReferenceId(spend.transactionId)) return

        val allowanceBack = if (entry.createdAt.isBefore(wallet.periodStart)) {
            0
        } else {
            minOf(-entry.allowanceDelta, properties.allowanceFor(wallet.tier) - wallet.allowanceRemaining)
                .coerceAtLeast(0)
        }
        val bonusBack = -entry.bonusDelta
        wallet.allowanceRemaining += allowanceBack
        wallet.bonusBalance += bonusBack
        wallet.updatedAt = now()
        record(
            spend.userId, CreditTransactionType.REFUND,
            allowanceDelta = allowanceBack, bonusDelta = bonusBack,
            action = entry.action, referenceId = spend.transactionId,
        )
    }

    /** Support/comp credits; they go to the bonus balance and never expire. */
    @Transactional
    fun grantBonus(userId: Long, amount: Int, note: String?): CreditBalance {
        require(amount in 1..MAX_GRANT) { "amount must be between 1 and $MAX_GRANT" }
        val wallet = currentWallet(userId)
        // Overflow would wrap negative (and trip the column's >= 0 check); refuse instead
        wallet.bonusBalance = try {
            Math.addExact(wallet.bonusBalance, amount)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("bonus balance would overflow")
        }
        wallet.updatedAt = now()
        record(userId, CreditTransactionType.ADMIN_GRANT, bonusDelta = amount, note = note?.take(NOTE_MAX))
        logger.info { "Granted $amount bonus credits to userId=$userId" }
        return wallet.toBalance()
    }

    // ── Wallet lifecycle ─────────────────────────────────────────────────────────────────────

    /** The locked, settled wallet; created with the signup bonus on first access. */
    private fun currentWallet(userId: Long): CreditWallet {
        val level = featureAccessService.accessLevel(userId)
        val now = now()
        val wallet = walletRepository.findForUpdate(userId) ?: create(userId, level, now)
        settle(wallet, level, now)
        return wallet
    }

    private fun create(userId: Long, level: AccessLevel, now: Instant): CreditWallet {
        val allowance = properties.allowanceFor(level)
        val bonus = properties.signupBonus
        val created = walletRepository.insertIfAbsent(
            userId, level.name, allowance, now, oneMonthAfter(now), bonus, now,
        ) == 1
        if (created) {
            record(userId, CreditTransactionType.ALLOWANCE_RESET, allowanceDelta = allowance, note = level.name)
            if (bonus > 0) record(userId, CreditTransactionType.SIGNUP_BONUS, bonusDelta = bonus)
            logger.info { "Created credit wallet for userId=$userId: tier=$level, allowance=$allowance, bonus=$bonus" }
        }
        return checkNotNull(walletRepository.findForUpdate(userId)) { "Credit wallet missing for userId=$userId" }
    }

    private fun settle(wallet: CreditWallet, level: AccessLevel, now: Instant) {
        val allowance = properties.allowanceFor(level)
        val currentAllowance = properties.allowanceFor(wallet.tier)
        when {
            !now.isBefore(wallet.periodEnd) -> {
                // Contiguous periods keep the reset date stable, however long the user was away
                var start = wallet.periodEnd
                while (!now.isBefore(oneMonthAfter(start))) start = oneMonthAfter(start)
                startPeriod(wallet, level, start, now)
            }
            allowance > currentAllowance -> upgrade(wallet, level, allowance, now)
            allowance < currentAllowance -> {
                val clamped = minOf(wallet.allowanceRemaining, allowance)
                val delta = clamped - wallet.allowanceRemaining
                wallet.tier = level
                wallet.allowanceRemaining = clamped
                wallet.updatedAt = now
                if (delta != 0) {
                    record(
                        wallet.userId,
                        CreditTransactionType.ALLOWANCE_ADJUSTED,
                        allowanceDelta = delta,
                        note = level.name,
                    )
                }
            }
            wallet.tier != level -> wallet.tier = level
        }
    }

    /**
     * Raises the allowance for a mid-period upgrade without restarting the period. The allowance
     * becomes the new tier's monthly amount less what this period's spends already took from it
     * (refunds counted), so flipping between tiers (e.g. toggling auto-renew) can't refill it:
     * the most a period ever yields is the highest tier's allowance.
     */
    private fun upgrade(wallet: CreditWallet, level: AccessLevel, allowance: Int, now: Instant) {
        val spentThisPeriod = -transactionRepository.sumAllowanceDeltaSince(
            wallet.userId, USAGE_TYPES, wallet.periodStart,
        ).coerceAtMost(0L)
        val target = (allowance.toLong() - spentThisPeriod).coerceIn(0L, allowance.toLong()).toInt()
        val delta = (target - wallet.allowanceRemaining).coerceAtLeast(0)
        wallet.tier = level
        wallet.allowanceRemaining += delta
        wallet.updatedAt = now
        if (delta != 0) {
            record(wallet.userId, CreditTransactionType.ALLOWANCE_ADJUSTED, allowanceDelta = delta, note = level.name)
        }
        logger.info { "Upgraded credit tier for userId=${wallet.userId} to $level: +$delta allowance" }
    }

    private fun startPeriod(wallet: CreditWallet, level: AccessLevel, start: Instant, now: Instant) {
        val allowance = properties.allowanceFor(level)
        val delta = allowance - wallet.allowanceRemaining
        wallet.tier = level
        wallet.allowanceRemaining = allowance
        wallet.periodStart = start
        wallet.periodEnd = oneMonthAfter(start)
        wallet.updatedAt = now
        record(wallet.userId, CreditTransactionType.ALLOWANCE_RESET, allowanceDelta = delta, note = level.name)
    }

    private fun record(
        userId: Long,
        type: CreditTransactionType,
        allowanceDelta: Int = 0,
        bonusDelta: Int = 0,
        action: CreditAction? = null,
        referenceId: Long? = null,
        note: String? = null,
    ): CreditTransaction = transactionRepository.save(
        CreditTransaction(
            userId = userId,
            type = type,
            action = action,
            allowanceDelta = allowanceDelta,
            bonusDelta = bonusDelta,
            referenceId = referenceId,
            note = note,
            createdAt = now(),
        )
    )

    private fun CreditWallet.toBalance() = CreditBalance(
        tier = tier,
        allowanceRemaining = allowanceRemaining,
        monthlyAllowance = properties.allowanceFor(tier),
        bonusBalance = bonusBalance,
        periodEndsAt = periodEnd,
    )

    private fun now(): Instant = Instant.now(clock)

    private fun oneMonthAfter(instant: Instant): Instant =
        instant.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()

    private companion object {
        const val NOTE_MAX = 128

        /** Largest single admin grant; a typo shouldn't mint millions of credits. */
        const val MAX_GRANT = 100_000

        /** Ledger rows that reflect actual use of the allowance (spends and their refunds). */
        val USAGE_TYPES = listOf(CreditTransactionType.SPEND, CreditTransactionType.REFUND)
    }
}

data class CreditBalance(
    val tier: AccessLevel,
    val allowanceRemaining: Int,
    val monthlyAllowance: Int,
    val bonusBalance: Int,
    val periodEndsAt: Instant,
) {
    val balance: Int get() = allowanceRemaining + bonusBalance
}

/** Receipt of a [CreditService.spend], needed to refund it. */
data class CreditSpend(val transactionId: Long, val userId: Long, val cost: Int)
