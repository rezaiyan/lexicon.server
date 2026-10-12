package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.notification.rescue.ComebackRescueScheduler
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class NotificationScheduledTasks(
    private val userRepository: UserRepository,
    private val notificationTimingService: NotificationTimingService,
    private val smartNotificationDispatcher: SmartNotificationDispatcher,
    private val reviewReminderDispatcher: ReviewReminderDispatcher,
    private val engagementSegmentScheduler: EngagementSegmentScheduler,
    private val comebackRescueScheduler: ComebackRescueScheduler,
) {
    @Scheduled(cron = "0 30 0 * * *")          // 00:30 UTC nightly
    fun refreshNotificationSchedules() {
        logger.info { "Starting nightly notification schedule refresh" }
        try {
            val activeUsers = userRepository.findAllActiveUsersWithPushTokens()
            notificationTimingService.refreshSchedulesForAllUsers(activeUsers)
            logger.info { "Notification schedule refresh complete — ${activeUsers.size} users processed" }
        } catch (e: Exception) {
            logger.error(e) { "Error in notification schedule refresh" }
        }
    }

    @Scheduled(cron = "0 0 1 * * *")           // 01:00 UTC nightly (after timing refresh)
    fun refreshEngagementSegments() {
        logger.info { "Starting nightly engagement segment refresh" }
        val activeUsers = try {
            userRepository.findAllActiveUsersWithPushTokens()
        } catch (e: Exception) {
            logger.error(e) { "Error loading users for nightly engagement refresh" }
            return
        }
        try {
            engagementSegmentScheduler.refreshAll(activeUsers)
        } catch (e: Exception) {
            logger.error(e) { "Error in engagement segment refresh" }
        }
        // After segments, so a rescue is planned against today's state; independent failure
        try {
            comebackRescueScheduler.planAll(activeUsers)
        } catch (e: Exception) {
            logger.error(e) { "Error in comeback rescue planning" }
        }
    }

    @Scheduled(cron = "0 0/30 * * * *")        // every 30 minutes
    fun dispatchSmartNotifications() {
        try {
            smartNotificationDispatcher.dispatchForCurrentHour()
        } catch (e: Exception) {
            logger.error(e) { "Error in smart notification dispatch" }
        }
    }

    @Scheduled(cron = "0 0/30 * * * *")        // every 30 minutes
    fun dispatchStreakSavers() {
        try {
            smartNotificationDispatcher.dispatchStreakSaversForCurrentHour()
        } catch (e: Exception) {
            logger.error(e) { "Error in streak saver dispatch" }
        }
    }

    @Scheduled(cron = "0 0/30 * * * *")        // every 30 minutes
    fun dispatchReviewReminders() {
        try {
            reviewReminderDispatcher.dispatchForCurrentHour()
        } catch (e: Exception) {
            logger.error(e) { "Error in review reminder dispatch" }
        }
    }
}
