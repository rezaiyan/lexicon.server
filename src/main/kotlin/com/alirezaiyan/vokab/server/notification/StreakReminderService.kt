package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.ai.AiService
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

@Service
class StreakReminderService(
    private val userRepository: UserRepository,
    private val dailyActivityRepository: DailyActivityRepository,
    private val pushNotificationService: PushNotificationService,
    private val aiService: AiService,
    private val userProgressService: UserProgressService,
    private val clock: Clock
) {

    @Transactional(readOnly = true)
    fun findUsersNeedingReminder(): List<User> {
        val today = LocalDate.now(clock)
        val usersWithActiveStreaks = userRepository.findByCurrentStreakGreaterThanAndActiveTrue(0)

        logger.info { "Found ${usersWithActiveStreaks.size} users with active streaks" }

        val usersNeedingReminder = usersWithActiveStreaks.filter { user ->
            !dailyActivityRepository.existsByUserAndActivityDate(user, today)
        }

        logger.info { "Found ${usersNeedingReminder.size} users needing reminder (no activity today)" }

        return usersNeedingReminder
    }

    fun sendReminderForUser(user: User) {
        try {
            val progressStats = userProgressService.calculateProgressStats(user.requireId())
            val message = aiService.generateStreakReminderMessage(
                currentStreak = user.currentStreak,
                userName = user.name,
                progressStats = progressStats
            )

            val data = mapOf(
                "type" to "streak_reminder",
                "currentStreak" to user.currentStreak.toString()
            )
            val results = pushNotificationService.sendNotificationToUser(
                userId = user.requireId(),
                title = "Don't lose your streak!",
                body = message,
                data = data,
                category = NotificationCategory.USER
            )
            if (results.any { it.success }) {
                logger.debug { "Reminder sent successfully to user=${user.id} (streak: ${user.currentStreak})" }
            } else {
                logger.warn { "Failed to send reminder to user=${user.id}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error sending streak reminder to user=${user.id}" }
            try {
                pushNotificationService.sendNotificationToUser(
                    userId = user.requireId(),
                    title = "Don't lose your streak!",
                    body = "You have a ${user.currentStreak}-day streak. Complete your review today to keep it going!",
                    data = mapOf("type" to "streak_reminder", "currentStreak" to user.currentStreak.toString()),
                    category = NotificationCategory.USER
                )
            } catch (fallbackError: Exception) {
                logger.error(fallbackError) { "Failed to send fallback reminder to user=${user.id}" }
            }
        }
    }

    fun sendReminderNotifications() {
        logger.info { "Starting streak reminder notification job" }

        val usersNeedingReminder = findUsersNeedingReminder()

        if (usersNeedingReminder.isEmpty()) {
            logger.info { "No users need reminders today" }
            return
        }

        var successCount = 0
        var failureCount = 0

        usersNeedingReminder.forEach { user ->
            try {
                sendReminderForUser(user)
                successCount++
            } catch (e: Exception) {
                failureCount++
                logger.error(e) { "Error sending reminder to user=${user.id}" }
            }
        }

        logger.info { "Streak reminder job completed. Success: $successCount, Failures: $failureCount" }
    }
}
