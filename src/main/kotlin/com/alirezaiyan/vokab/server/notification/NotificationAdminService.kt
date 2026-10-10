package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettings
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.user.requireId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/** Read side and maintenance actions behind the push notification admin dashboard. */
@Service
class NotificationAdminService(
    private val notificationLogRepository: NotificationLogRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val pushTokenRepository: PushTokenRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationScheduledTasks: NotificationScheduledTasks,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun getAudience(): NotificationAudienceDto {
        val byPlatform = pushTokenRepository.countActiveByPlatform()
            .associate { row -> row[0].toString() to row[1] as Long }
        return NotificationAudienceDto(
            activeTokens = byPlatform.values.sum(),
            activeTokensByPlatform = byPlatform,
            reachableUsers = pushTokenRepository.findReachableUserIds().size.toLong(),
            optedOutUsers = pushTokenRepository.countReachableOptedOutUsers(),
        )
    }

    @Transactional(readOnly = true)
    fun getLogs(userId: Long?, type: String?, page: Int, size: Int): NotificationLogPageDto {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..MAX_PAGE_SIZE) { "size must be between 1 and $MAX_PAGE_SIZE" }
        val typeFilter = type?.takeIf { it.isNotBlank() }
        val result = notificationLogRepository.search(userId, typeFilter, PageRequest.of(page, size))
        return NotificationLogPageDto(
            items = result.content.map { it.toDto() },
            page = page,
            size = size,
            totalItems = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getLogTypes(): List<String> = notificationLogRepository.findDistinctTypes()

    /** Looks a user up by numeric id or by email. */
    @Transactional(readOnly = true)
    fun getUserDetail(query: String): NotificationUserDetailDto {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "query is required" }
        val user = trimmed.toLongOrNull()
            ?.let { userRepository.findById(it).orElse(null) }
            ?: userRepository.findByEmail(trimmed).orElse(null)
            ?: throw NoSuchElementException("No user matches the query")
        return user.toDetail()
    }

    /** Lifts ignore-based backoff so the smart dispatcher considers the user again. */
    @Transactional
    fun clearSuppression(userId: Long): NotificationUserDetailDto {
        val schedule = notificationScheduleRepository.findByUserId(userId)
            ?: throw NoSuchElementException("User $userId has no notification schedule")
        schedule.suppressedUntil = null
        schedule.consecutiveIgnores = 0
        schedule.updatedAt = Instant.now(clock)
        notificationScheduleRepository.save(schedule)
        logger.info { "Admin cleared notification suppression for user=$userId" }
        return schedule.user.toDetail()
    }

    /**
     * Runs a scheduled job now. The dispatch jobs keep their own once-a-day guards, so an extra
     * run only reaches users due this hour who haven't had their push yet.
     */
    fun runJob(job: NotificationJob): NotificationJobRunDto {
        val startedAt = Instant.now(clock)
        logger.info { "Admin triggered notification job $job" }
        when (job) {
            NotificationJob.SMART_DISPATCH -> notificationScheduledTasks.dispatchSmartNotifications()
            NotificationJob.STREAK_SAVERS -> notificationScheduledTasks.dispatchStreakSavers()
            NotificationJob.REVIEW_REMINDERS -> notificationScheduledTasks.dispatchReviewReminders()
            NotificationJob.REFRESH_SCHEDULES -> notificationScheduledTasks.refreshNotificationSchedules()
            NotificationJob.REFRESH_SEGMENTS -> notificationScheduledTasks.refreshEngagementSegments()
        }
        return NotificationJobRunDto(
            job = job,
            startedAt = startedAt,
            durationMs = Duration.between(startedAt, Instant.now(clock)).toMillis(),
        )
    }

    private fun User.toDetail(): NotificationUserDetailDto {
        val userId = requireId()
        val since = Instant.now(clock).minus(30, ChronoUnit.DAYS)
        val recent = notificationLogRepository.findRecentByUserId(userId, since)
        return NotificationUserDetailDto(
            userId = userId,
            email = email,
            name = name,
            active = active,
            currentStreak = currentStreak,
            lastLoginAt = lastLoginAt,
            settings = userSettingsRepository.findByUserId(userId)?.toAdminDto(),
            schedule = notificationScheduleRepository.findByUserId(userId)?.toAdminDto(),
            tokens = pushTokenRepository.findByUser(this)
                .sortedWith(compareByDescending<PushToken> { it.active }.thenByDescending { it.updatedAt })
                .map { it.toAdminDto() },
            last30DaysSent = recent.size,
            last30DaysOpened = recent.count { it.openedAt != null },
            recentLogs = recent.take(RECENT_LOGS).map { it.toDto() },
        )
    }

    companion object {
        const val MAX_PAGE_SIZE = 200
        private const val RECENT_LOGS = 20
    }
}

private fun NotificationLog.toDto() = NotificationLogDto(
    id = id,
    userId = userId,
    notificationType = notificationType,
    title = title,
    body = body,
    sentAt = sentAt,
    openedAt = openedAt,
    dataPayload = dataPayload,
)

private fun PushToken.toAdminDto() = PushTokenAdminDto(
    id = requireNotNull(id) { "Persisted push token without id" },
    platform = platform,
    deviceId = deviceId,
    active = active,
    tokenPreview = token.take(12) + "…",
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun UserSettings.toAdminDto() = NotificationSettingsAdminDto(
    notificationsEnabled = notificationsEnabled,
    reviewRemindersEnabled = reviewRemindersEnabled,
    notificationFrequency = notificationFrequency,
    timezone = timezone,
)

private fun NotificationSchedule.toAdminDto() = NotificationScheduleAdminDto(
    optimalSendHour = optimalSendHour,
    timezoneOffsetHrs = timezoneOffsetHrs,
    engagementSegment = engagementSegment,
    consecutiveIgnores = consecutiveIgnores,
    suppressedUntil = suppressedUntil,
    lastSentDate = lastSentDate,
    lastSentType = lastSentType,
    aiAction = aiAction,
    aiIntervalDays = aiIntervalDays,
    aiContentHint = aiContentHint,
)
