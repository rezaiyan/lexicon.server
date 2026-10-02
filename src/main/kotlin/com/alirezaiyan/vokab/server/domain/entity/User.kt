package com.alirezaiyan.vokab.server.domain.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "users")
@JsonIgnoreProperties(value = ["pushTokens"], allowGetters = false)
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(nullable = false, unique = true)
    val email: String,
    
    @Column(nullable = false)
    val name: String,

    @Column(name = "google_id", unique = true)
    val googleId: String? = null,

    @Column(name = "apple_id", unique = true)
    val appleId: String? = null,
    
    // Subscription columns are updatable = false on purpose: services save whole `user.copy(...)`
    // snapshots (login, streaks, profile), which would otherwise silently revert a webhook or
    // sync that landed in between. Change them only via UserRepository.updateSubscription /
    // linkRevenueCatUserId.
    @Column(name = "revenuecat_user_id", unique = true, updatable = false)
    val revenueCatUserId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,

    @Column(name = "subscription_expires_at", updatable = false)
    val subscriptionExpiresAt: Instant? = null,

    /** Premium granted outside the store (test users, comps). Written only via UserRepository.updateGrant. */
    @Column(name = "premium_grant_until", updatable = false)
    val premiumGrantUntil: Instant? = null,

    /** Why the grant exists: test_email, legacy_grant, manual, ci. */
    @Column(name = "premium_grant_reason", length = 64, updatable = false)
    val premiumGrantReason: String? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
    
    @Column(name = "last_login_at")
    val lastLoginAt: Instant? = null,
    
    @Column(name = "current_streak", nullable = false)
    val currentStreak: Int = 0,

    @Column(name = "longest_streak", nullable = false)
    val longestStreak: Int = 0,

    @Column(name = "display_alias", length = 50)
    val displayAlias: String? = null,

    @Column(name = "profile_image_url", length = 512)
    val profileImageUrl: String? = null,

    @Column(name = "first_word_added_at")
    val firstWordAddedAt: Instant? = null,

    @Column(name = "first_review_at")
    val firstReviewAt: Instant? = null,

    @Column(name = "signup_country", length = 2)
    val signupCountry: String? = null,

    @Column(name = "last_login_country", length = 2)
    val lastLoginCountry: String? = null,

    @Column(nullable = false)
    val active: Boolean = true,
    
    @JsonIgnore
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val pushTokens: MutableList<PushToken> = mutableListOf()
) {
    /**
     * Override toString() to prevent LazyInitializationException
     * Kotlin data class auto-generates toString() that includes all fields
     * We exclude pushTokens to avoid lazy loading after session is closed
     */
    override fun toString(): String {
        return "User(id=$id, email='$email', name='$name', subscriptionStatus=$subscriptionStatus)"
    }
}

enum class SubscriptionStatus {
    FREE,
    TRIAL,
    ACTIVE,
    EXPIRED,
    CANCELLED
}

