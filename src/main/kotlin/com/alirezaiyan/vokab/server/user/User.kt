package com.alirezaiyan.vokab.server.user

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.notification.PushToken

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,
    
    @Column(nullable = false, unique = true)
    var email: String,
    
    @Column(nullable = false)
    var name: String,

    @Column(name = "google_id", unique = true)
    var googleId: String? = null,

    @Column(name = "apple_id", unique = true)
    var appleId: String? = null,
    
    // Store-owned columns: see the properties of the same name in the class body.
    revenueCatUserId: String? = null,
    subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    subscriptionExpiresAt: Instant? = null,
    premiumGrantUntil: Instant? = null,
    premiumGrantReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    
    @Column(name = "current_streak", nullable = false)
    var currentStreak: Int = 0,

    @Column(name = "longest_streak", nullable = false)
    var longestStreak: Int = 0,

    @Column(name = "display_alias", length = 50)
    var displayAlias: String? = null,

    @Column(name = "profile_image_url", length = 512)
    var profileImageUrl: String? = null,

    @Column(name = "first_word_added_at")
    var firstWordAddedAt: Instant? = null,

    @Column(name = "first_review_at")
    var firstReviewAt: Instant? = null,

    @Column(name = "signup_country", length = 2)
    var signupCountry: String? = null,

    @Column(name = "last_login_country", length = 2)
    var lastLoginCountry: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,
    
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val pushTokens: MutableList<PushToken> = mutableListOf()
) : JpaEntity<Long>() {

    // Store-owned columns are `updatable = false` on purpose: services save whole user rows (login,
    // streaks, profile) from possibly stale instances, which would otherwise silently revert a webhook
    // or sync that landed in between. They are written only by UserRepository.updateSubscription /
    // updateGrant / linkRevenueCatUserId (or the initial INSERT); setters are non-public so the in-memory
    // copy can only change through the mirror* functions below, after that repository write.
    // (`protected`, not `private`: entities are open for Hibernate proxies, and Kotlin forbids private
    // setters on open properties.)

    @Column(name = "revenuecat_user_id", unique = true, updatable = false)
    var revenueCatUserId: String? = revenueCatUserId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    var subscriptionStatus: SubscriptionStatus = subscriptionStatus
        protected set

    @Column(name = "subscription_expires_at", updatable = false)
    var subscriptionExpiresAt: Instant? = subscriptionExpiresAt
        protected set

    /** Premium granted outside the store (test users, comps). */
    @Column(name = "premium_grant_until", updatable = false)
    var premiumGrantUntil: Instant? = premiumGrantUntil
        protected set

    /** Why the grant exists: test_email, legacy_grant, manual, ci. */
    @Column(name = "premium_grant_reason", length = 64, updatable = false)
    var premiumGrantReason: String? = premiumGrantReason
        protected set

    /** When the store couldn't charge the renewal; null once a payment succeeds. */
    @Column(name = "subscription_billing_issue_at", updatable = false)
    var subscriptionBillingIssueAt: Instant? = null
        protected set

    /** Google Play pause: when the subscription resumes; null when not paused. */
    @Column(name = "subscription_pause_resumes_at", updatable = false)
    var subscriptionPauseResumesAt: Instant? = null
        protected set

    /** Reflects a UserRepository.updateSubscription write (or sets the value for a not-yet-inserted user). */
    fun mirrorSubscription(status: SubscriptionStatus, expiresAt: Instant?) {
        subscriptionStatus = status
        subscriptionExpiresAt = expiresAt
    }

    /** Reflects a UserRepository.updateSubscriptionIssues write. */
    fun mirrorSubscriptionIssues(billingIssueAt: Instant?, pauseResumesAt: Instant?) {
        subscriptionBillingIssueAt = billingIssueAt
        subscriptionPauseResumesAt = pauseResumesAt
    }

    /** Reflects a UserRepository.updateGrant write (or sets the value for a not-yet-inserted user). */
    fun mirrorGrant(until: Instant?, reason: String?) {
        premiumGrantUntil = until
        premiumGrantReason = reason
    }

    /** Reflects a UserRepository.linkRevenueCatUserId write. */
    fun mirrorRevenueCatUserId(id: String) {
        revenueCatUserId = id
    }

    // Id only: email and name are PII and must not reach logs
    override fun toString(): String = "User(id=$id, subscriptionStatus=$subscriptionStatus)"
}

/** Id of a persisted user. A missing id is a programming error (unsaved entity), not bad input. */
fun User.requireId(): Long = checkNotNull(id) { "User has no id; it was never persisted" }

enum class SubscriptionStatus {
    FREE,
    TRIAL,
    ACTIVE,
    EXPIRED,
    CANCELLED
}

