package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.subscription.AccessLevel
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Tier changes against an in-memory wallet and ledger, so the credit-farming rules run without a
 * database. [CreditServiceTest] covers the same service against the real schema.
 */
class CreditServiceTierChangeTest {

    private val clock = MutableClock()
    private var level = AccessLevel.FREE
    private val ledger = mutableListOf<CreditTransaction>()
    private var wallet: CreditWallet? = null
    private val properties = CreditProperties(
        signupBonus = 15,
        freeMonthlyAllowance = 5,
        trialMonthlyAllowance = 30,
        premiumMonthlyAllowance = 300,
        costs = mapOf(CreditAction.PHOTO_EXTRACTION to 3),
    )
    private lateinit var service: CreditService

    @BeforeEach
    fun setUp() {
        val walletRepository = mockk<CreditWalletRepository> {
            every { findForUpdate(USER_ID) } answers { wallet }
            every { insertIfAbsent(USER_ID, any(), any(), any(), any(), any(), any()) } answers {
                wallet = testWallet(tier = AccessLevel.valueOf(secondArg()), allowance = thirdArg(), periodStart = arg(3))
                1
            }
        }
        val transactionRepository = mockk<CreditTransactionRepository> {
            every { save(any()) } answers {
                val row = firstArg<CreditTransaction>()
                val saved = CreditTransaction(
                    id = ledger.size + 1L, userId = row.userId, type = row.type, action = row.action,
                    allowanceDelta = row.allowanceDelta, bonusDelta = row.bonusDelta,
                    referenceId = row.referenceId, note = row.note, createdAt = row.createdAt,
                )
                ledger += saved
                saved
            }
            every { sumAllowanceDeltaSince(USER_ID, any(), any()) } answers {
                val types = secondArg<Collection<CreditTransactionType>>()
                val since = thirdArg<Instant>()
                ledger.filter { it.type in types && !it.createdAt.isBefore(since) }.sumOf { it.allowanceDelta.toLong() }
            }
        }
        val featureAccess = mockk<FeatureAccessService> { every { accessLevel(any()) } answers { level } }
        service = CreditService(walletRepository, transactionRepository, properties, featureAccess, clock)
    }

    @Test
    fun `toggling auto-renew between trial and premium does not refill the allowance`() {
        level = AccessLevel.TRIAL
        service.balance(USER_ID)
        level = AccessLevel.PREMIUM // e.g. a cancelled trial that used to count as premium
        repeat(100) { service.spend(USER_ID, CreditAction.PHOTO_EXTRACTION) } // all 300 used

        repeat(5) {
            clock.advance(Duration.ofMinutes(1))
            level = AccessLevel.TRIAL
            service.balance(USER_ID)
            clock.advance(Duration.ofMinutes(1))
            level = AccessLevel.PREMIUM
            service.balance(USER_ID)
        }

        val balance = service.balance(USER_ID)
        assertEquals(0, balance.allowanceRemaining)
        assertEquals(300, totalAllowanceGranted())
    }

    @Test
    fun `the period's reset date is not moved by an upgrade`() {
        level = AccessLevel.TRIAL
        val periodEnd = service.balance(USER_ID).periodEndsAt
        clock.advance(Duration.ofDays(3))
        level = AccessLevel.PREMIUM

        assertEquals(periodEnd, service.balance(USER_ID).periodEndsAt)
    }

    @Test
    fun `a genuine trial to paid conversion gets the paid allowance less what the trial used`() {
        level = AccessLevel.TRIAL
        service.spend(USER_ID, CreditAction.PHOTO_EXTRACTION) // 30 -> 27
        clock.advance(Duration.ofDays(7))
        level = AccessLevel.PREMIUM

        val balance = service.balance(USER_ID)

        assertEquals(AccessLevel.PREMIUM, balance.tier)
        assertEquals(297, balance.allowanceRemaining)
    }

    @Test
    fun `an unused trial converting to paid gets the full paid allowance`() {
        level = AccessLevel.TRIAL
        service.balance(USER_ID)
        level = AccessLevel.PREMIUM

        assertEquals(300, service.balance(USER_ID).allowanceRemaining)
    }

    @Test
    fun `the next period grants the full allowance again`() {
        level = AccessLevel.PREMIUM
        repeat(100) { service.spend(USER_ID, CreditAction.PHOTO_EXTRACTION) }
        clock.advance(Duration.ofDays(31))

        assertEquals(300, service.balance(USER_ID).allowanceRemaining)
    }

    @Test
    fun `grantBonus refuses an amount that would overflow the bonus balance`() {
        service.balance(USER_ID)
        wallet!!.bonusBalance = Int.MAX_VALUE - 10

        assertThrows<IllegalArgumentException> { service.grantBonus(USER_ID, 100, null) }
        assertEquals(Int.MAX_VALUE - 10, wallet!!.bonusBalance)
    }

    @Test
    fun `grantBonus refuses an amount above the cap`() {
        assertThrows<IllegalArgumentException> { service.grantBonus(USER_ID, 100_001, null) }
    }

    private fun totalAllowanceGranted(): Int = ledger
        .filter { it.type == CreditTransactionType.ALLOWANCE_RESET || it.allowanceDelta > 0 && it.type == CreditTransactionType.ALLOWANCE_ADJUSTED }
        .sumOf { it.allowanceDelta }

    private fun testWallet(tier: AccessLevel, allowance: Int, periodStart: Instant) = CreditWallet(
        userId = USER_ID,
        tier = tier,
        allowanceRemaining = allowance,
        periodStart = periodStart,
        periodEnd = periodStart.plus(Duration.ofDays(30)),
        bonusBalance = properties.signupBonus,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
    )

    private companion object {
        const val USER_ID = 7L
    }
}
