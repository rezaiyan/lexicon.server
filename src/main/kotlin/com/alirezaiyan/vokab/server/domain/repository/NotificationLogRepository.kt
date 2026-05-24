package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.NotificationLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface NotificationLogRepository : JpaRepository<NotificationLog, Long> {
    fun findTopByUserIdOrderBySentAtDesc(userId: Long): NotificationLog?

    fun findTop3ByUserIdOrderBySentAtDesc(userId: Long): List<NotificationLog>

    fun findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId: Long): NotificationLog?

    @Query("""
        SELECT nl FROM NotificationLog nl
        WHERE nl.userId = :userId AND nl.sentAt >= :since
        ORDER BY nl.sentAt DESC
    """)
    fun findRecentByUserId(userId: Long, since: Instant): List<NotificationLog>

    fun countByUserIdAndOpenedAtIsNotNullAndSentAtAfter(userId: Long, since: Instant): Long

    fun existsByUserIdAndNotificationTypeAndSentAtAfter(userId: Long, notificationType: String, since: Instant): Boolean

    fun countBySentAtAfter(since: Instant): Long

    fun countBySentAtAfterAndOpenedAtIsNotNull(since: Instant): Long

    /**
     * Returns [notificationType, totalSent, totalOpened] grouped by type for admin stats.
     */
    @Query("""
        SELECT nl.notificationType, COUNT(nl), COUNT(nl.openedAt)
        FROM NotificationLog nl
        WHERE nl.sentAt >= :since
        GROUP BY nl.notificationType
    """)
    fun findTypeBreakdownSince(since: Instant): List<Array<Any>>
}
