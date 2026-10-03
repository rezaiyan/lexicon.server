package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.service.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.service.push.PushNotificationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime
import com.alirezaiyan.vokab.server.domain.entity.requireId

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
    private val clock: Clock
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

    private fun dispatchForUser(schedule: NotificationSchedule) {
        val user   = schedule.user
        val userId = user.id ?: error("User id is null for schedule=${schedule.id}")

        val segment        = schedule.engagementSegment
        val isColdOrDormant = segment == "COLD" || segment == "DORMANT"

        // COLD/DORMANT users: honour AI decision first
        if (isColdOrDormant) {
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

            if (type == NotificationType.PROGRESS_MILESTONE) {
                val stats = userProgressService.calculateProgressStats(user)
                milestoneDetector.recordMilestoneSnapshot(user, stats)
            }

            logger.info { "Sent $type (segment=${ schedule.engagementSegment}) to user=$userId" }
        } else {
            discardLog(logId)
            logger.warn { "Push delivery failed for user=$userId, type=$type" }
        }
    }

    /** A log for an undelivered push would later count as an ignored notification. */
    private fun discardLog(logId: Long?) {
        if (logId == null) return
        runCatching { notificationEngagementService.deleteLog(logId) }
            .onFailure { e -> logger.error(e) { "Failed to discard notification log=$logId" } }
    }
}
