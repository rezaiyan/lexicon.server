package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.subscription.AccessLevel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A user's current credit balances. Mutated only by [CreditService] while holding the row lock;
 * every change is mirrored by a [CreditTransaction].
 *
 * [tier] is the access level the current period's allowance was granted for, so an upgrade or
 * downgrade can be detected on the next access.
 */
@Entity
@Table(name = "credit_wallets")
class CreditWallet(
    @Id
    @Column(name = "user_id")
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var tier: AccessLevel,

    @Column(name = "allowance_remaining", nullable = false)
    var allowanceRemaining: Int,

    @Column(name = "period_start", nullable = false)
    var periodStart: Instant,

    @Column(name = "period_end", nullable = false)
    var periodEnd: Instant,

    @Column(name = "bonus_balance", nullable = false)
    var bonusBalance: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : JpaEntity<Long>() {
    override val id: Long get() = userId

    /** The id is assigned by us, never by the database. */
    override fun isTransient(): Boolean = false

    val balance: Int get() = allowanceRemaining + bonusBalance
}
