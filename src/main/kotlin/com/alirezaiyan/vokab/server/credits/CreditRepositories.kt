package com.alirezaiyan.vokab.server.credits

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface CreditWalletRepository : JpaRepository<CreditWallet, Long> {

    /** Row lock for the rest of the transaction: concurrent spends of one user run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM CreditWallet w WHERE w.userId = :userId")
    fun findForUpdate(userId: Long): CreditWallet?

    /**
     * Creates the wallet unless it exists. Insert-or-skip instead of check-then-insert, so two
     * first requests of a new user racing each other can't fail on the primary key.
     * Returns 1 when this call created it. No conflict target, so the SQL also runs on H2 (dev
     * profile); the primary key is the table's only unique constraint, so the meaning is the same.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO credit_wallets
                (user_id, tier, allowance_remaining, period_start, period_end, bonus_balance, created_at, updated_at)
            VALUES (:userId, :tier, :allowance, :periodStart, :periodEnd, :bonus, :now, :now)
            ON CONFLICT DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        userId: Long,
        tier: String,
        allowance: Int,
        periodStart: Instant,
        periodEnd: Instant,
        bonus: Int,
        now: Instant,
    ): Int
}

@Repository
interface CreditTransactionRepository : JpaRepository<CreditTransaction, Long> {
    fun existsByReferenceId(referenceId: Long): Boolean
}
