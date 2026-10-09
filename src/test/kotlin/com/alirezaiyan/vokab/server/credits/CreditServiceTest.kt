package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.shared.InsufficientCreditsException
import com.alirezaiyan.vokab.server.subscription.AccessLevel
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import io.mockk.every
import jakarta.persistence.EntityManager
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant

/** Runs against the migrated PostgreSQL schema: the upsert, row lock and constraints are real. */
@DataJpaTest
@ActiveProfiles("test")
class CreditServiceTest {

    @Autowired private lateinit var walletRepository: CreditWalletRepository
    @Autowired private lateinit var transactionRepository: CreditTransactionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var entityManager: EntityManager

    private val clock = MutableClock()
    private var level = AccessLevel.FREE
    private val properties = CreditProperties(
        signupBonus = 15,
        freeMonthlyAllowance = 5,
        trialMonthlyAllowance = 30,
        premiumMonthlyAllowance = 300,
        costs = mapOf(CreditAction.PHOTO_EXTRACTION to 3, CreditAction.AI_SUGGESTION to 1, CreditAction.TEXT_TRANSLATION to 0),
    )
    private lateinit var service: CreditService
    private var userId = 0L

    @BeforeEach
    fun setUp() {
        val featureAccess = mockk<FeatureAccessService> { every { accessLevel(any()) } answers { level } }
        service = CreditService(walletRepository, transactionRepository, properties, featureAccess, clock)
        userId = insertUser("credits@example.com")
    }

    // ── Wallet creation ──────────────────────────────────────────────────────────────────────

    @Test
    fun `balance for a new user creates the wallet with the free allowance and signup bonus`() {
        val balance = service.balance(userId)

        assertEquals(AccessLevel.FREE, balance.tier)
        assertEquals(5, balance.allowanceRemaining)
        assertEquals(15, balance.bonusBalance)
        assertEquals(20, balance.balance)
        assertEquals(Instant.parse("2026-07-17T10:00:00Z"), balance.periodEndsAt)
        assertEquals(listOf(CreditTransactionType.ALLOWANCE_RESET, CreditTransactionType.SIGNUP_BONUS), ledgerTypes())
    }

    @Test
    fun `the signup bonus is granted once however often the wallet is read`() {
        service.balance(userId)
        service.balance(userId)

        assertEquals(1, ledgerTypes().count { it == CreditTransactionType.SIGNUP_BONUS })
    }

    // ── Spending ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `spend drains the expiring allowance before the bonus`() {
        service.spend(userId, CreditAction.PHOTO_EXTRACTION) // 5 → 2 allowance
        service.spend(userId, CreditAction.PHOTO_EXTRACTION) // 2 allowance + 1 bonus

        val balance = service.balance(userId)
        assertEquals(0, balance.allowanceRemaining)
        assertEquals(14, balance.bonusBalance)
    }

    @Test
    fun `spend beyond the balance is refused and changes nothing`() {
        setBuckets(allowance = 1, bonus = 1)

        val error = assertThrows<InsufficientCreditsException> { service.spend(userId, CreditAction.PHOTO_EXTRACTION) }

        assertEquals(3, error.required)
        assertEquals(2, error.available)
        assertEquals(2, service.balance(userId).balance)
        assertEquals(0, ledgerTypes().count { it == CreditTransactionType.SPEND })
    }

    @Test
    fun `a free action spends nothing and leaves no ledger row`() {
        service.balance(userId)
        val before = ledgerTypes()

        assertNull(service.spend(userId, CreditAction.TEXT_TRANSLATION))
        assertEquals(before, ledgerTypes())
        assertEquals(20, service.balance(userId).balance)
    }

    // ── Refunds ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `refund returns credits to the buckets they came from, once`() {
        service.spend(userId, CreditAction.PHOTO_EXTRACTION)
        val spend = requireNotNull(service.spend(userId, CreditAction.PHOTO_EXTRACTION)) // 2 allowance + 1 bonus

        service.refund(spend)
        service.refund(spend)

        val balance = service.balance(userId)
        assertEquals(2, balance.allowanceRemaining)
        assertEquals(15, balance.bonusBalance)
        assertEquals(1, ledgerTypes().count { it == CreditTransactionType.REFUND })
    }

    @Test
    fun `refund of allowance spent in an ended period restores only the bonus part`() {
        setBuckets(allowance = 1, bonus = 15)
        val spend = requireNotNull(service.spend(userId, CreditAction.PHOTO_EXTRACTION)) // 1 allowance + 2 bonus
        clock.advance(Duration.ofDays(31))

        service.refund(spend)

        val balance = service.balance(userId)
        assertEquals(5, balance.allowanceRemaining) // the fresh period's allowance, nothing added
        assertEquals(15, balance.bonusBalance)
    }

    // ── Periods and tier changes ─────────────────────────────────────────────────────────────

    @Test
    fun `a new period resets the allowance without rollover, anchored to the original period`() {
        service.spend(userId, CreditAction.AI_SUGGESTION)
        clock.advance(Duration.ofDays(75)) // 2026-08-31: two periods ended

        val balance = service.balance(userId)

        assertEquals(5, balance.allowanceRemaining)
        assertEquals(Instant.parse("2026-09-17T10:00:00Z"), balance.periodEndsAt)
    }

    @Test
    fun `upgrading mid-period starts a fresh period with the bigger allowance`() {
        service.spend(userId, CreditAction.PHOTO_EXTRACTION)
        clock.advance(Duration.ofDays(10))
        level = AccessLevel.PREMIUM

        val balance = service.balance(userId)

        assertEquals(AccessLevel.PREMIUM, balance.tier)
        assertEquals(300, balance.allowanceRemaining)
        assertEquals(TEST_NOW.plus(Duration.ofDays(10)).plus(Duration.ofDays(30)), balance.periodEndsAt)
    }

    @Test
    fun `downgrading clamps the remaining allowance and keeps the period`() {
        level = AccessLevel.PREMIUM
        val periodEnd = service.balance(userId).periodEndsAt
        clock.advance(Duration.ofDays(5))
        level = AccessLevel.FREE

        val balance = service.balance(userId)

        assertEquals(AccessLevel.FREE, balance.tier)
        assertEquals(5, balance.allowanceRemaining)
        assertEquals(periodEnd, balance.periodEndsAt)
        assertEquals(CreditTransactionType.ALLOWANCE_ADJUSTED, ledgerTypes().last())
    }

    @Test
    fun `trial converting to paid tops the allowance up to premium`() {
        level = AccessLevel.TRIAL
        assertEquals(30, service.balance(userId).allowanceRemaining)

        level = AccessLevel.PREMIUM
        assertEquals(300, service.balance(userId).allowanceRemaining)
    }

    // ── Grants ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `grantBonus adds non-expiring credits`() {
        val balance = service.grantBonus(userId, 50, "support")

        assertEquals(65, balance.bonusBalance)
        assertEquals(CreditTransactionType.ADMIN_GRANT, ledgerTypes().last())
    }

    @Test
    fun `grantBonus rejects a non-positive amount`() {
        assertThrows<IllegalArgumentException> { service.grantBonus(userId, 0, null) }
    }

    @Test
    fun `the ledger always sums to the wallet balance`() {
        service.spend(userId, CreditAction.PHOTO_EXTRACTION)
        val spend = requireNotNull(service.spend(userId, CreditAction.PHOTO_EXTRACTION))
        service.refund(spend)
        level = AccessLevel.PREMIUM
        service.spend(userId, CreditAction.AI_SUGGESTION)
        service.grantBonus(userId, 4, null)

        val ledgerSum = jdbc.queryForObject(
            "SELECT SUM(allowance_delta + bonus_delta) FROM credit_transactions WHERE user_id = ?", Int::class.java, userId,
        )
        assertEquals(service.balance(userId).balance, ledgerSum)
    }

    /** Puts the wallet in a given state; the session is cleared so the service reads the new row. */
    private fun setBuckets(allowance: Int, bonus: Int) {
        service.balance(userId)
        entityManager.flush()
        jdbc.update(
            "UPDATE credit_wallets SET allowance_remaining = ?, bonus_balance = ? WHERE user_id = ?",
            allowance, bonus, userId,
        )
        entityManager.clear()
    }

    private fun ledgerTypes(): List<CreditTransactionType> =
        transactionRepository.findAll().filter { it.userId == userId }.sortedBy { it.id }.map { it.type }

    private fun insertUser(email: String): Long {
        jdbc.update("INSERT INTO users (email, name) VALUES (?, 'Test')", email)
        return requireNotNull(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.java, email))
    }
}
