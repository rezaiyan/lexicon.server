package com.alirezaiyan.vokab.server.notification

import io.micrometer.core.instrument.MeterRegistry
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

/** Push data key carrying the notification_log id; clients echo it back when the user taps. */
const val NOTIFICATION_LOG_ID_KEY = "notification_log_id"

@Service
class SmartNotificationDispatcher(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationTypeSelector: NotificationTypeSelector,
    private val notificationContentBuilder: NotificationContentBuilder,
    private val pushNotificationService: PushNotificationService,
    private val milestoneDetector: MilestoneDetector,
    private val userProgressService: UserProgressService,
    private val notificationEngagementService: NotificationEngagementService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    fun dispatchForCurrentHour() {
        val hour = LocalTime.now(clock).hour
        val schedules = notificationScheduleRepository.findUsersToNotifyAtHour(hour)
        logger.info { "Smart dispatch: hour=$hour, candidates=${schedules.size}" }

        for (schedule in schedules) {
            runCatching { dispatchForUser(schedule) }
                .onFailure { logger.error(it) { "Dispatch failed for user=${schedule.user.id}" } }
        }
    }

    /**
     * Evening streak saver: one push to streak holders who haven't studied yet today, sent at
     * the latest daytime hour for them that still leaves time before the streak day ends.
     * Runs alongside [dispatchForCurrentHour] and doesn't count against its one-a-day limit:
     * for a user with a streak at stake this is the push most worth getting.
     */
    fun dispatchStreakSaversForCurrentHour() {
        val hour = LocalTime.now(clock).hour
        val offsets = TIMEZONE_OFFSETS.filter { streakSaverUtcHour(it) == hour }
        if (offsets.isEmpty()) return

        val today = LocalDate.now(clock)
        val schedules = notificationScheduleRepository.findStreakSaverCandidates(offsets, MIN_STREAK_TO_SAVE)
        logger.info { "Streak saver dispatch: hour=$hour, offsets=$offsets, candidates=${schedules.size}" }

        for (schedule in schedules) {
            runCatching {
                val alreadyWarned = schedule.lastSentDate == today &&
                    schedule.lastSentType == NotificationType.STREAK_RISK.name
                if (alreadyWarned || notificationTypeSelector.studiedToday(schedule.user)) return@runCatching
                sendAndRecord(schedule, NotificationType.STREAK_RISK, contentHint = null)
            }.onFailure { logger.error(it) { "Streak saver failed for user=${schedule.user.id}" } }
        }
    }

    private fun dispatchForUser(schedule: NotificationSchedule) {
        val user   = schedule.user
        val userId = user.id ?: error("User id is null for schedule=${schedule.id}")

        val segment        = schedule.engagementSegment
        val isColdOrDormant = segment == "COLD" || segment == "DORMANT"

        // COLD/DORMANT users: honour AI decision first — unless they came back today on their
        // own, when a "come back" push would be wrong (the selector handles them instead)
        if (isColdOrDormant && !notificationTypeSelector.studiedToday(user)) {
            when (schedule.aiAction) {
                "pause" -> {
                    val pauseDays = schedule.aiIntervalDays?.toLong() ?: 3L
                    notificationScheduleRepository.findByUserId(userId)?.let { s ->
                        s.suppressedUntil = LocalDate.now(clock).plusDays(pauseDays)
                        notificationScheduleRepository.save(s)
                    }
                    logger.debug { "AI pause for user=$userId for ${pauseDays}d" }
                    return
                }
                "motivate" -> {
                    sendAndRecord(schedule, NotificationType.MOTIVATION, schedule.aiContentHint)
                    return
                }
                // "send" or null → fall through to rule-based selection
            }
        }

        val type = notificationTypeSelector.selectType(user, schedule)
        if (type == NotificationType.NONE) {
            logger.debug { "No notification selected for user=$userId" }
            return
        }

        sendAndRecord(schedule, type, contentHint = null)
    }

    private fun sendAndRecord(
        schedule: NotificationSchedule,
        type: NotificationType,
        contentHint: String?
    ) {
        val user    = schedule.user
        val userId  = user.requireId()
        val payload = notificationContentBuilder.build(user, type, contentHint)

        // The log is created before the push so its id can ride in the payload and come back
        // with the tap (POST /notifications/{id}/opened). Without a log we still send — only
        // open tracking for this one notification is lost.
        val logId = runCatching {
            notificationEngagementService.saveLog(
                userId           = userId,
                notificationType = type.name,
                title            = payload.title,
                body             = payload.body,
                // Strip null bytes: PostgreSQL JSONB rejects U+0000 in string values
                dataPayload      = objectMapper.writeValueAsString(
                    payload.data.mapValues { (_, v) -> v.replace("\u0000", "") }
                )
            )
        }.onFailure { e ->
            logger.error(e) { "Notification log persist failed for user=$userId — sending without open tracking" }
        }.getOrNull()

        val data = if (logId != null) payload.data + (NOTIFICATION_LOG_ID_KEY to logId.toString()) else payload.data
        val results = runCatching {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title  = payload.title,
                body   = payload.body,
                data   = data
            )
        }.onFailure { discardLog(logId) }.getOrThrow()

        val sent = results.any { it.success }
        if (sent) {
            notificationEngagementService.recordSend(schedule, type.name, currentLogId = logId)
            meterRegistry.counter("notifications.sent", "type", type.name).increment()

            if (type == NotificationType.PROGRESS_MILESTONE) {
                val stats = userProgressService.calculateProgressStats(user.requireId())
                milestoneDetector.recordMilestoneSnapshot(user, stats)
            }

            logger.info { "Sent $type (segment=${ schedule.engagementSegment}) to user=$userId" }
        } else {
            discardLog(logId)
            meterRegistry.counter("notifications.failed", "type", type.name).increment()
            logger.warn { "Push delivery failed for user=$userId, type=$type" }
        }
    }

    /** A log for an undelivered push would later count as an ignored notification. */
    private fun discardLog(logId: Long?) {
        if (logId == null) return
        runCatching { notificationEngagementService.deleteLog(logId) }
            .onFailure { e -> logger.error(e) { "Failed to discard notification log=$logId" } }
    }

    companion object {
        /** Streaks of a single day aren't worth an extra evening interruption. */
        const val MIN_STREAK_TO_SAVE = 2

        private val TIMEZONE_OFFSETS = -12..14
        private const val EARLIEST_UTC_HOUR = 10
        /** Dispatch runs at :00 and :30, so this leaves 3.5–4 hours before the streak day ends. */
        private const val LATEST_UTC_HOUR = 20
        private val DAYTIME_LOCAL_HOURS = 8..21

        /**
         * The UTC hour users at [offsetHrs] get the streak saver: the latest hour of the UTC
         * streak day that is still daytime for them, or null when none is.
         */
        fun streakSaverUtcHour(offsetHrs: Int): Int? =
            (LATEST_UTC_HOUR downTo EARLIEST_UTC_HOUR).firstOrNull { utcHour ->
                (utcHour + offsetHrs + 24) % 24 in DAYTIME_LOCAL_HOURS
            }
    }
}
