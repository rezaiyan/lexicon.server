package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface PushTokenRepository : JpaRepository<PushToken, Long> {
    fun findByToken(token: String): Optional<PushToken>
    fun findByUserAndActiveTrue(user: User): List<PushToken>
    fun findByUser(user: User): List<PushToken>

    /**
     * First half of an upsert keyed on the unique `token`; always follow with [reassignToken].
     * Split from `ON CONFLICT ... DO UPDATE` so the SQL also runs on H2 (dev profile);
     * `DO NOTHING` + UPDATE is equally race-safe in PostgreSQL.
     */
    @Modifying
    @Query(
        """
        INSERT INTO push_tokens (user_id, token, platform, device_id, created_at, updated_at, active)
        VALUES (:userId, :token, :platform, :deviceId, NOW(), NOW(), true)
        ON CONFLICT DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertTokenIfAbsent(
        @Param("userId") userId: Long,
        @Param("token") token: String,
        @Param("platform") platform: String,
        @Param("deviceId") deviceId: String?
    ): Int

    /** Second half of the upsert: the device's token now belongs to this user and is active. */
    @Modifying
    @Query(
        """
        UPDATE push_tokens
        SET user_id = :userId, platform = :platform, device_id = :deviceId, updated_at = NOW(), active = true
        WHERE token = :token
        """,
        nativeQuery = true
    )
    fun reassignToken(
        @Param("userId") userId: Long,
        @Param("token") token: String,
        @Param("platform") platform: String,
        @Param("deviceId") deviceId: String?
    ): Int

    @Modifying
    @Query("UPDATE PushToken p SET p.active = false WHERE p.token = :token")
    fun deactivateByToken(token: String): Int

    @Modifying
    @Query("UPDATE PushToken p SET p.active = false WHERE p.user = :user")
    fun deactivateAllByUser(user: User): Int

    /** Returns [platform, count] for active tokens, for the admin dashboard. */
    @Query("SELECT p.platform, COUNT(p) FROM PushToken p WHERE p.active = true GROUP BY p.platform")
    fun countActiveByPlatform(): List<Array<Any>>

    /** Active accounts with at least one active token: everyone a push can reach. */
    @Query("SELECT DISTINCT p.user.id FROM PushToken p WHERE p.active = true AND p.user.active = true")
    fun findReachableUserIds(): List<Long>

    /** Reachable users who switched notifications off in their settings. */
    @Query("""
        SELECT COUNT(DISTINCT p.user.id) FROM PushToken p
        WHERE p.active = true AND p.user.active = true
          AND EXISTS (
            SELECT us FROM UserSettings us
            WHERE us.user = p.user
              AND (us.notificationsEnabled = false OR us.notificationFrequency = 'OFF')
          )
    """)
    fun countReachableOptedOutUsers(): Long
}
