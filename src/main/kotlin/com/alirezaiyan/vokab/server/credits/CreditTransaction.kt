package com.alirezaiyan.vokab.server.credits

import com.alirezaiyan.vokab.server.shared.JpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One immutable ledger row: how much each bucket of a wallet moved, and why. */
@Entity
@Table(name = "credit_transactions")
class CreditTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val type: CreditTransactionType,

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    val action: CreditAction? = null,

    @Column(name = "allowance_delta", nullable = false)
    val allowanceDelta: Int = 0,

    @Column(name = "bonus_delta", nullable = false)
    val bonusDelta: Int = 0,

    /** For [CreditTransactionType.REFUND]: the spend it reverses. */
    @Column(name = "reference_id", unique = true)
    val referenceId: Long? = null,

    @Column(length = 128)
    val note: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) : JpaEntity<Long>()

enum class CreditTransactionType {
    /** A new period started: allowance replaced by the tier's monthly amount. */
    ALLOWANCE_RESET,

    /** Allowance clamped after a downgrade (premium ended mid-period). */
    ALLOWANCE_ADJUSTED,
    SIGNUP_BONUS,
    ADMIN_GRANT,
    SPEND,
    REFUND,
}
