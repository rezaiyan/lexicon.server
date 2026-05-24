package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationLog
import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.repository.NotificationLogRepository
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.domain.repository.UserSettingsRepository
import com.alirezaiyan.vokab.server.presentation.dto.Last7DaysDto
import com.alirezaiyan.vokab.server.presentation.dto.NotificationAdminStatsDto
import com.alirezaiyan.vokab.server.presentation.dto.SuppressedUsersDto
import com.alirezaiyan.vokab.server.presentation.dto.TypeStatsDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Service
class NotificationEngagementService(
    private val notificationLogRepository: NotificationLogRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val userSettingsRepository: UserSettingsRepository
) {
    /**
     * Called when a user taps a notification.
     * - Marks notification_log.opened_at
     * - Resets consecutive_ignores to 0 in notification_schedule
     * - Clears suppression if currently active
     */
    @Transactional
    fun recordOpen(userId: Long, notificationLogId: Long) {
        notificationLogRepository.findById(notificationLogId).ifPresent { log ->
            if (log.userId == userId && log.openedAt == null) {
                log.openedAt = Instant.now()
                notificationLogRepository.save(log)
            }
        }

        notificationScheduleRepository.findByUserId(userId)?.let { schedule ->
            schedule.consecutiveIgnores = 0
            schedule.suppressedUntil    = null
            // Reset to WARM so the nightly batch re-evaluates from a fresh start
            schedule.engagementSegment  = "WARM"
            // Invalidate AI cache — next nightly batch will re-query if user goes cold again
            schedule.aiDecidedAt        = null
            schedule.updatedAt          = Instant.now()
            notificationScheduleRepository.save(schedule)
        }

        logger.debug { "Notification opened: user=$userId log=$notificationLogId" }
    }

    /**
     * Called by SmartNotificationDispatcher after a successful push send.
     * Commits schedule state (lastSentDate, suppression, ignore counter) in its own transaction.
     * The log insert is intentionally separate — see saveLog() — so a log failure cannot
     * roll back lastSentDate and cause the user to receive a second notification.
     *
     * Suppression schedule (days of silence after consecutive ignores):
     *  1st ignore    → suppressed for 1 day
     *  2nd ignore    → suppressed for 2 days
     *  3–5  ignores  → suppressed for 3 days
     *  6–9  ignores  → suppressed for 7 days
     *  10–14 ignores → suppressed for 14 days
     *  15+  ignores  → suppressed for 30 days (dormant nurture)
     */
    @Transactional
    fun recordSend(
        schedule: NotificationSchedule,
        notificationType: String
    ) {
        val userId = schedule.user.id!!

        // Check previous log BEFORE saving the new one
        val previousLog = notificationLogRepository.findTopByUserIdOrderBySentAtDesc(userId)
        if (previousLog != null && previousLog.openedAt == null) {
            val ignoreCount = schedule.consecutiveIgnores + 1
            schedule.consecutiveIgnores = ignoreCount
            schedule.suppressedUntil = computeSuppressedUntil(ignoreCount)
        }

        // Apply frequency-based minimum cadence if not already suppressed longer
        val freqSuppression = computeFrequencySuppression(userId)
        if (freqSuppression != null) {
            val current = schedule.suppressedUntil
            if (current == null || current.isBefore(freqSuppression)) {
                schedule.suppressedUntil = freqSuppression
            }
        }

        schedule.lastSentDate = LocalDate.now(ZoneOffset.UTC)
        schedule.lastSentType = notificationType
        schedule.updatedAt = Instant.now()
        notificationScheduleRepository.save(schedule)

        logger.debug { "Notification send recorded: user=$userId type=$notificationType" }
    }

    /**
     * Persists the notification log entry in its own transaction.
     * Called after recordSend() so that a failure here cannot roll back the schedule update.
     */
    @Transactional
    fun saveLog(
        userId: Long,
        notificationType: String,
        title: String?,
        body: String?,
        dataPayload: String?
    ) {
        notificationLogRepository.save(
            NotificationLog(
                userId = userId,
                notificationType = notificationType,
                title = title,
                body = body,
                dataPayload = dataPayload
            )
        )
    }

    private fun computeSuppressedUntil(ignoreCount: Int): LocalDate? {
        val today = LocalDate.now(ZoneOffset.UTC)
        return when {
            ignoreCount < 1  -> null
            ignoreCount <= 2 -> today.plusDays(ignoreCount.toLong()) // 1st→+1d, 2nd→+2d
            ignoreCount <= 5 -> today.plusDays(3)
            ignoreCount <= 9 -> today.plusDays(7)
            ignoreCount <= 14 -> today.plusDays(14)
            else             -> today.plusDays(30)
        }
    }

    private fun computeFrequencySuppression(userId: Long): LocalDate? {
        val settings = userSettingsRepository.findByUserId(userId) ?: return null
        val today = LocalDate.now(ZoneOffset.UTC)
        return when (settings.notificationFrequency) {
            "EVERY_OTHER_DAY" -> today.plusDays(1)
            "WEEKLY"          -> today.plusDays(6)
            "OFF"             -> today.plusDays(365)
            else              -> null
        }
    }

    /**
     * Returns engagement stats for a user over a rolling window.
     */
    @Transactional(readOnly = true)
    fun getEngagementStats(userId: Long, windowDays: Long = 30): EngagementStats {
        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)
        val recentLogs = notificationLogRepository.findRecentByUserId(userId, since)
        val totalSent   = recentLogs.size
        val totalOpened = recentLogs.count { it.openedAt != null }
        val openRate    = if (totalSent > 0) totalOpened.toFloat() / totalSent else 0f

        return EngagementStats(
            totalSent = totalSent,
            totalOpened = totalOpened,
            openRatePercent = (openRate * 100).toInt(),
            consecutiveIgnores = notificationScheduleRepository.findByUserId(userId)?.consecutiveIgnores ?: 0
        )
    }

    /**
     * Returns aggregated notification health stats for the admin dashboard.
     */
    @Transactional(readOnly = true)
    fun getAdminStats(): NotificationAdminStatsDto {
        val since = Instant.now().minus(7, ChronoUnit.DAYS)

        val totalSent   = notificationLogRepository.countBySentAtAfter(since)
        val totalOpened = notificationLogRepository.countBySentAtAfterAndOpenedAtIsNotNull(since)
        val openRate    = if (totalSent > 0) ((totalOpened.toDouble() / totalSent) * 100).toInt() else 0

        val typeBreakdown = notificationLogRepository.findTypeBreakdownSince(since)
            .associate { row ->
                val type   = row[0] as String
                val sent   = row[1] as Long
                val opened = row[2] as Long
                val rate   = if (sent > 0) ((opened.toDouble() / sent) * 100).toInt() else 0
                type to TypeStatsDto(sent = sent, opened = opened, openRate = rate)
            }

        return NotificationAdminStatsDto(
            totalActiveSchedules = notificationScheduleRepository.count(),
            suppressedUsers = SuppressedUsersDto(
                day3  = notificationScheduleRepository.countSuppressed3Day(),
                day7  = notificationScheduleRepository.countSuppressed7Day(),
                day14 = notificationScheduleRepository.countSuppressed14Day(),
                day30 = notificationScheduleRepository.countSuppressed30Day()
            ),
            last7Days = Last7DaysDto(
                totalSent        = totalSent,
                totalOpened      = totalOpened,
                openRatePercent  = openRate,
                typeBreakdown    = typeBreakdown
            )
        )
    }

    @Transactional(readOnly = true)
    fun getDaysSinceLastOpen(userId: Long): Long? {
        val log = notificationLogRepository
            .findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId) ?: return null
        return ChronoUnit.DAYS.between(log.openedAt, Instant.now())
    }

    data class EngagementStats(
        val totalSent: Int,
        val totalOpened: Int,
        val openRatePercent: Int,
        val consecutiveIgnores: Int
    )
}
