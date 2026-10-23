package com.alirezaiyan.vokab.server.notification

import io.micrometer.core.instrument.MeterRegistry
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import io.github.oshai.kotlinlogging.KotlinLogging
import com.alirezaiyan.vokab.server.study.UserProgressService
import java.time.Clock
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalTime

private val logger = KotlinLogging.logger {}
private val REVIEW_REMINDER = NotificationType.REVIEW_REMINDER.name

@Service
class ReviewReminderDispatcher(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationTypeSelector: NotificationTypeSelector,
    private val notificationContentBuilder: NotificationContentBuilder,
    private val userProgressService: UserProgressService,
    private val pushNotificationService: PushNotificationService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    fun dispatchForCurrentHour() {
        val hour = LocalTime.now(clock).hour
        val schedules = notificationScheduleRepository.findUsersForReviewReminders(hour)
        logger.info { "Review reminder dispatch: hour=$hour, candidates=${schedules.size}" }
        for (schedule in schedules) {
            runCatching { dispatchForUser(schedule) }
                .onFailure { logger.error(it) { "Review reminder dispatch failed for user=${schedule.user.id}" } }
        }
    }

    private fun dispatchForUser(schedule: NotificationSchedule) {
        val user = schedule.user
        val userId = user.id ?: error("User id is null for schedule=${schedule.id}")
        // A review reminder with nothing to review, or after today's review, is pure noise
        if (notificationTypeSelector.studiedToday(user)) return
        if (userProgressService.calculateProgressStats(userId).dueCards == 0) return

        val payload = notificationContentBuilder.build(user, NotificationType.REVIEW_REMINDER)
        val results = pushNotificationService.sendNotificationToUser(
            userId = userId,
            title = payload.title,
            body = payload.body,
            data = payload.data
        )
        val sent = results.any { it.success }
        if (sent) {
            schedule.lastSentDate = LocalDate.now(clock)
            notificationScheduleRepository.save(schedule)
            meterRegistry.counter("notifications.sent", "type", REVIEW_REMINDER).increment()
            logger.info { "Sent REVIEW_REMINDER to user=${user.id}" }
        } else {
            meterRegistry.counter("notifications.failed", "type", REVIEW_REMINDER).increment()
            logger.warn { "Push delivery failed for user=${user.id}, type=REVIEW_REMINDER" }
        }
    }
}
