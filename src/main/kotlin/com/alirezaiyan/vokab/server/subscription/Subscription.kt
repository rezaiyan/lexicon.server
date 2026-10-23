package com.alirezaiyan.vokab.server.subscription

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(name = "subscriptions")
class Subscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    
    @Column(name = "revenuecat_subscription_id", unique = true)
    val revenueCatSubscriptionId: String? = null,
    
    @Column(name = "product_id", nullable = false)
    var productId: String,
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SubscriptionStatus,
    
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
    
    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,
    
    @Column(name = "is_trial")
    var isTrial: Boolean = false,
    
    @Column(name = "auto_renew")
    var autoRenew: Boolean = true,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) : JpaEntity<Long>()

