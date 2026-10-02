package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): Optional<User>
    fun findByGoogleId(googleId: String): Optional<User>
    fun findByAppleId(appleId: String): Optional<User>
    fun findByRevenueCatUserId(revenueCatUserId: String): Optional<User>
    fun findByCurrentStreakGreaterThanAndActiveTrue(currentStreak: Int): List<User>

    // ── Subscription state: the only write paths for the updatable = false columns on User ──

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE User u SET u.subscriptionStatus = :status, u.subscriptionExpiresAt = :expiresAt, " +
            "u.updatedAt = :now WHERE u.id = :id"
    )
    fun updateSubscription(
        @Param("id") id: Long,
        @Param("status") status: SubscriptionStatus,
        @Param("expiresAt") expiresAt: Instant?,
        @Param("now") now: Instant,
    ): Int

    /** Sets (or with nulls, revokes) a premium grant. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE User u SET u.premiumGrantUntil = :until, u.premiumGrantReason = :reason, " +
            "u.updatedAt = :now WHERE u.id = :id"
    )
    fun updateGrant(
        @Param("id") id: Long,
        @Param("until") until: Instant?,
        @Param("reason") reason: String?,
        @Param("now") now: Instant,
    ): Int

    /** Links a RevenueCat app user id once; never overwrites an existing link. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE User u SET u.revenueCatUserId = :rcId, u.updatedAt = :now " +
            "WHERE u.id = :id AND u.revenueCatUserId IS NULL"
    )
    fun linkRevenueCatUserId(@Param("id") id: Long, @Param("rcId") rcId: String, @Param("now") now: Instant): Int

    /** Moves store-derived statuses whose paid period has ended to EXPIRED (keeps stats honest). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE User u SET u.subscriptionStatus = :expired, u.updatedAt = :now " +
            "WHERE u.subscriptionStatus IN :lapsable AND u.subscriptionExpiresAt IS NOT NULL " +
            "AND u.subscriptionExpiresAt < :now"
    )
    fun expireLapsedSubscriptions(
        @Param("now") now: Instant,
        @Param("lapsable") lapsable: Collection<SubscriptionStatus>,
        @Param("expired") expired: SubscriptionStatus,
    ): Int

    @Query("SELECT u.id FROM User u WHERE u.revenueCatUserId IS NOT NULL ORDER BY u.id")
    fun findIdsLinkedToRevenueCat(): List<Long>

    @Query("SELECT u.id FROM User u WHERE u.active = true ORDER BY u.id")
    fun findActiveUserIds(): List<Long>

    @Query(
        "SELECT u FROM User u WHERE u.active = true AND u.email NOT IN :excludedEmails " +
            "ORDER BY (" +
            "(SELECT COUNT(w) FROM Word w WHERE w.level = 6 AND w.user = u) * 10 " +
            "+ u.currentStreak * 3 " +
            "+ u.longestStreak * 2" +
            ") DESC"
    )
    fun findTopUsersByScore(pageable: Pageable, @Param("excludedEmails") excludedEmails: List<String>): List<User>

    @Query(
        "SELECT COUNT(u) + 1 FROM User u WHERE u.active = true AND u.email NOT IN :excludedEmails AND (" +
            "(SELECT COUNT(w) FROM Word w WHERE w.level = 6 AND w.user = u) * 10 " +
            "+ u.currentStreak * 3 " +
            "+ u.longestStreak * 2" +
            ") > :userScore"
    )
    fun findUserRankByScore(@Param("userScore") userScore: Long, @Param("excludedEmails") excludedEmails: List<String>): Long

    @Query("""
        SELECT DISTINCT u FROM User u
        JOIN u.pushTokens pt
        WHERE u.active = true AND pt.active = true
    """)
    fun findAllActiveUsersWithPushTokens(): List<User>
}