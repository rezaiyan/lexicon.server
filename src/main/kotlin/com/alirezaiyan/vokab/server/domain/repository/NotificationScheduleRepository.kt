package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface NotificationScheduleRepository : JpaRepository<NotificationSchedule, Long> {
    fun findByUser(user: User): NotificationSchedule?
    fun findByUserId(userId: Long): NotificationSchedule?

    @Query("SELECT ns.user.id FROM NotificationSchedule ns")
    fun findAllScheduledUserIds(): Set<Long>

    @Query("""
        SELECT ns FROM NotificationSchedule ns
        WHERE ns.optimalSendHour = :hour
          AND (ns.suppressedUntil IS NULL OR ns.suppressedUntil < CURRENT_DATE)
          AND (ns.lastSentDate IS NULL OR ns.lastSentDate < CURRENT_DATE)
          AND EXISTS (
            SELECT us FROM UserSettings us
            WHERE us.user = ns.user
              AND us.notificationsEnabled = true
              AND us.notificationFrequency <> 'OFF'
          )
    """)
    fun findUsersToNotifyAtHour(hour: Int): List<NotificationSchedule>

    @Query("SELECT COUNT(ns) FROM NotificationSchedule ns WHERE ns.suppressedUntil >= CURRENT_DATE AND ns.consecutiveIgnores BETWEEN 3 AND 5")
    fun countSuppressed3Day(): Long

    @Query("SELECT COUNT(ns) FROM NotificationSchedule ns WHERE ns.suppressedUntil >= CURRENT_DATE AND ns.consecutiveIgnores BETWEEN 6 AND 9")
    fun countSuppressed7Day(): Long

    @Query("SELECT COUNT(ns) FROM NotificationSchedule ns WHERE ns.suppressedUntil >= CURRENT_DATE AND ns.consecutiveIgnores BETWEEN 10 AND 14")
    fun countSuppressed14Day(): Long

    @Query("SELECT COUNT(ns) FROM NotificationSchedule ns WHERE ns.suppressedUntil >= CURRENT_DATE AND ns.consecutiveIgnores >= 15")
    fun countSuppressed30Day(): Long

    @Query("""
        SELECT ns FROM NotificationSchedule ns
        WHERE ns.optimalSendHour = :hour
          AND (ns.lastSentDate IS NULL OR ns.lastSentDate < CURRENT_DATE)
          AND EXISTS (
            SELECT us FROM UserSettings us
            WHERE us.user = ns.user
              AND us.reviewRemindersEnabled = true
              AND (us.notificationsEnabled = false OR us.notificationFrequency = 'OFF')
          )
    """)
    fun findUsersForReviewReminders(@Param("hour") hour: Int): List<NotificationSchedule>

    /**
     * Returns schedules for COLD/DORMANT users whose AI decision is stale or missing.
     * Used by the nightly batch to determine who needs an AI advisor call.
     */
    @Query("""
        SELECT ns FROM NotificationSchedule ns
        WHERE ns.engagementSegment IN ('COLD', 'DORMANT')
          AND (ns.aiDecidedAt IS NULL OR ns.aiDecidedAt < :staleCutoff)
    """)
    fun findSchedulesNeedingAiRefresh(@Param("staleCutoff") staleCutoff: Instant): List<NotificationSchedule>
}
