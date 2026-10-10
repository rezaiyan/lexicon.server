package com.alirezaiyan.vokab.server.notification

import io.micrometer.core.instrument.MeterRegistry
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import com.alirezaiyan.vokab.server.user.requireId

private val logger = KotlinLogging.logger {}

@Service
class NotificationEngagementService(
    private val notificationLogRepository: NotificationLogRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val dailyActivityRepository: DailyActivityRepository,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Called when a user taps a notification.
     * - Marks notification_log.opened_at (first open only — repeats are no-ops)
     * - Resets consecutive_ignores to 0 in notification_schedule
     * - Clears suppression if currently active
     * @return false when the log doesn't exist or belongs to another user
     */
    @Transactional
    fun recordOpen(userId: Long, notificationLogId: Long): Boolean {
        val log = notificationLogRepository.findById(notificationLogId).orElse(null)
        if (log == null || log.userId != userId) return false
        // Repeated taps / retries are no-ops: only the first open counts as engagement
        if (log.openedAt != null) return true

        log.openedAt = Instant.now(clock)
        notificationLogRepository.save(log)
        meterRegistry.counter("notifications.opened").increment()

        notificationScheduleRepository.findByUserId(userId)?.let { schedule ->
            schedule.consecutiveIgnores = 0
            schedule.suppressedUntil    = null
            // Reset to WARM so the nightly batch re-evaluates from a fresh start
            schedule.engagementSegment  = "WARM"
            // Invalidate AI cache — next nightly batch will re-query if user goes cold again
            schedule.aiDecidedAt        = null
            schedule.updatedAt          = Instant.now(clock)
            notificationScheduleRepository.save(schedule)
        }

        logger.debug { "Notification opened: user=$userId log=$notificationLogId" }
        return true
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
        notificationType: String,
        currentLogId: Long? = null,
    ) {
        val userId = schedule.user.requireId()

        // Increment ignore counter if a notification was previously sent and the user neither
        // opened it nor studied since. Uses lastSentDate as the "sent-before" signal so
        // suppression works even when saveLog fails.
        // If a log record IS present and was opened, recordOpen() already reset the counter.
        // The log for this send already exists (its id travels in the push payload) — skip it.
        val previousLog = if (currentLogId != null) {
            notificationLogRepository.findFirstByUserIdAndIdNotOrderBySentAtDesc(userId, currentLogId)
        } else {
            notificationLogRepository.findTopByUserIdOrderBySentAtDesc(userId)
        }
        val wasOpened = previousLog?.openedAt != null
        val previousSentDate = schedule.lastSentDate
        if (!wasOpened && previousSentDate != null) {
            val sentDate = previousLog?.sentAt?.let { LocalDate.ofInstant(it, ZoneOffset.UTC) } ?: previousSentDate
            // Many learners read the push and open the app from its icon: the reminder worked
            // even though no tap was reported, so it must not push them into backoff.
            if (dailyActivityRepository.existsByUserIdAndActivityDateGreaterThanEqual(userId, sentDate)) {
                schedule.consecutiveIgnores = 0
            } else {
                val ignoreCount = schedule.consecutiveIgnores + 1
                schedule.consecutiveIgnores = ignoreCount
                schedule.suppressedUntil = computeSuppressedUntil(ignoreCount)
            }
        }

        // Apply frequency-based minimum cadence if not already suppressed longer
        val freqSuppression = computeFrequencySuppression(userId)
        if (freqSuppression != null) {
            val current = schedule.suppressedUntil
            if (current == null || current.isBefore(freqSuppression)) {
                schedule.suppressedUntil = freqSuppression
            }
        }

        schedule.lastSentDate = LocalDate.now(clock)
        schedule.lastSentType = notificationType
        schedule.updatedAt = Instant.now(clock)
        notificationScheduleRepository.save(schedule)

        logger.debug { "Notification send recorded: user=$userId type=$notificationType" }
    }

    /**
     * Persists the notification log entry in its own transaction.
     * Called before the push so the log id can travel in the payload; a failure here
     * only loses open tracking for this send, never the send itself.
     * @return the new log id
     */
    @Transactional
    fun saveLog(
        userId: Long,
        notificationType: String,
        title: String?,
        body: String?,
        dataPayload: String?
    ): Long =
        notificationLogRepository.save(
            NotificationLog(
                userId = userId,
                notificationType = notificationType,
                title = title,
                body = body,
                dataPayload = dataPayload,
                sentAt = Instant.now(clock),
            )
        ).id

    /** Removes a log pre-created for a push that was then not delivered. */
    @Transactional
    fun deleteLog(notificationLogId: Long) {
        notificationLogRepository.deleteById(notificationLogId)
    }

    private fun computeSuppressedUntil(ignoreCount: Int): LocalDate? {
        val today = LocalDate.now(clock)
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
        val today = LocalDate.now(clock)
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
        val since = Instant.now(clock).minus(windowDays, ChronoUnit.DAYS)
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
        val since = Instant.now(clock).minus(7, ChronoUnit.DAYS)

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
        return ChronoUnit.DAYS.between(log.openedAt, Instant.now(clock))
    }

    data class EngagementStats(
        val totalSent: Int,
        val totalOpened: Int,
        val openRatePercent: Int,
        val consecutiveIgnores: Int
    )
}
