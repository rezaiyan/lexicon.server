package com.alirezaiyan.vokab.server.ai

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(
    name = "daily_insights",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "date"])]
)
class DailyInsight(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    
    @Column(name = "insight_text", nullable = false, length = 500)
    val insightText: String,
    
    @Column(name = "generated_at", nullable = false)
    val generatedAt: Instant,
    
    @Column(name = "date", nullable = false)
    val date: String, // YYYY-MM-DD format
    
    @Column(name = "sent_via_push", nullable = false)
    var sentViaPush: Boolean = false,
    
    @Column(name = "push_sent_at")
    var pushSentAt: Instant? = null
) : JpaEntity<Long>() {
    constructor() : this(
        id = null,
        user = User(
            id = null,
            email = "",
            name = "",
            googleId = null,
            appleId = null,
            revenueCatUserId = null,
            subscriptionStatus = SubscriptionStatus.FREE,
            subscriptionExpiresAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            lastLoginAt = null,
            currentStreak = 0,
            active = true,
            pushTokens = mutableListOf()
        ),
        insightText = "",
        generatedAt = Instant.now(),
        date = "",
        sentViaPush = false,
        pushSentAt = null
    )
}
