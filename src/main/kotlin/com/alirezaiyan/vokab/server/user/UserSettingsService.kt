package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.notification.NotificationSchedule
import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.alirezaiyan.vokab.server.notification.NotificationEngagementService

private val logger = KotlinLogging.logger {}

@Service
class UserSettingsService(
    private val repo: UserSettingsRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationEngagementService: NotificationEngagementService,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun get(userId: Long): SettingsDto {
        val user = userRepository.getReferenceById(userId)
        val s = repo.findByUser(user) ?: repo.save(UserSettings(user = user))
        val schedule = notificationScheduleRepository.findByUser(user)
        val engagementStats = user.id?.let {
            runCatching { notificationEngagementService.getEngagementStats(it) }.getOrNull()
        }
        return s.toDto(schedule, engagementStats)
    }

    @Transactional
    fun update(userId: Long, dto: SettingsDto): SettingsDto {
        val user = userRepository.getReferenceById(userId)
        val current = repo.findByUser(user) ?: UserSettings(user = user)
        current.languageCode = dto.languageCode
        current.themeMode = dto.themeMode
        current.notificationsEnabled = dto.notificationsEnabled
        current.reviewRemindersEnabled = dto.reviewRemindersEnabled
        current.dailyReminderTime = dto.dailyReminderTime
        current.notificationFrequency = dto.notificationFrequency
        return repo.save(current).toDto(null, null)
    }
}

private fun UserSettings.toDto(
    schedule: NotificationSchedule?,
    engagementStats: NotificationEngagementService.EngagementStats?
) = SettingsDto(
    languageCode = languageCode,
    themeMode = themeMode,
    notificationsEnabled = notificationsEnabled,
    reviewRemindersEnabled = reviewRemindersEnabled,
    dailyReminderTime = dailyReminderTime,
    notificationFrequency = notificationFrequency,
    optimalSendHour = schedule?.optimalSendHour,
    dataConfidence = schedule?.dataConfidence,
    engagementStats = engagementStats?.let { stats ->
        EngagementStatsDto(
            openRatePercent = stats.openRatePercent,
            consecutiveIgnores = stats.consecutiveIgnores,
            suppressedUntil = schedule?.suppressedUntil
        )
    }
)
