package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.Platform
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

/** Who a push can reach right now: active tokens on active accounts. */
data class NotificationAudienceDto(
    val activeTokens: Long,
    val activeTokensByPlatform: Map<String, Long>,
    val reachableUsers: Long,
    /** Reachable users who turned notifications off; manual sends skip them unless told otherwise. */
    val optedOutUsers: Long,
)

data class NotificationLogDto(
    val id: Long,
    val userId: Long,
    val notificationType: String,
    val title: String?,
    val body: String?,
    val sentAt: Instant,
    val openedAt: Instant?,
    val dataPayload: String?,
)

data class NotificationLogPageDto(
    val items: List<NotificationLogDto>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

data class PushTokenAdminDto(
    val id: Long,
    val platform: Platform,
    val deviceId: String?,
    val active: Boolean,
    /** First characters only: the full token is a delivery credential. */
    val tokenPreview: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NotificationSettingsAdminDto(
    val notificationsEnabled: Boolean,
    val reviewRemindersEnabled: Boolean,
    val notificationFrequency: String,
    val timezone: String?,
)

data class NotificationScheduleAdminDto(
    val optimalSendHour: Int,
    val timezoneOffsetHrs: Int,
    val engagementSegment: String,
    val consecutiveIgnores: Int,
    val suppressedUntil: LocalDate?,
    val lastSentDate: LocalDate?,
    val lastSentType: String?,
    val aiAction: String?,
    val aiIntervalDays: Int?,
    val aiContentHint: String?,
)

data class NotificationUserDetailDto(
    val userId: Long,
    val email: String,
    val name: String,
    val active: Boolean,
    val currentStreak: Int,
    val lastLoginAt: Instant?,
    val settings: NotificationSettingsAdminDto?,
    val schedule: NotificationScheduleAdminDto?,
    val tokens: List<PushTokenAdminDto>,
    val last30DaysSent: Int,
    val last30DaysOpened: Int,
    val recentLogs: List<NotificationLogDto>,
)

enum class ManualPushAudience { USERS, ALL }

data class ManualPushRequest(
    val audience: ManualPushAudience = ManualPushAudience.USERS,

    /** Targets for [ManualPushAudience.USERS]: ids, emails, or both. */
    @field:Size(max = MAX_MANUAL_TARGETS, message = "At most $MAX_MANUAL_TARGETS user ids per send")
    val userIds: List<Long> = emptyList(),

    @field:Size(max = MAX_MANUAL_TARGETS, message = "At most $MAX_MANUAL_TARGETS emails per send")
    val emails: List<String> = emptyList(),

    @field:NotBlank(message = "Title is required")
    @field:Size(max = 200, message = "Title must be at most 200 characters")
    val title: String,

    @field:NotBlank(message = "Body is required")
    @field:Size(max = 1000, message = "Body must be at most 1000 characters")
    val body: String,

    /** Extra push data, e.g. a deep link the app routes on. */
    val data: Map<String, String> = emptyMap(),

    val imageUrl: String? = null,

    /** Skip users who turned notifications off. Only override for service messages. */
    val respectOptOut: Boolean = true,

    /** Resolve the audience and report who would get it, without sending anything. */
    val dryRun: Boolean = false,
) {
    companion object {
        const val MAX_MANUAL_TARGETS = 1000
    }
}

enum class ManualPushOutcome { SENT, WOULD_SEND, FAILED, NO_TOKENS, OPTED_OUT, NOT_FOUND }

data class ManualPushRecipientDto(
    /** Null when an email matched no account. */
    val userId: Long?,
    val target: String,
    val outcome: ManualPushOutcome,
    val devicesReached: Int = 0,
    val error: String? = null,
)

data class ManualPushResultDto(
    val dryRun: Boolean,
    val targeted: Int,
    val sent: Int,
    val failed: Int,
    val skipped: Int,
    /** Per-recipient detail, capped so a broadcast response stays small. */
    val recipients: List<ManualPushRecipientDto>,
    val recipientsTruncated: Boolean,
)

/** Scheduled notification jobs an admin can run on demand. */
enum class NotificationJob {
    SMART_DISPATCH,
    STREAK_SAVERS,
    REVIEW_REMINDERS,
    REFRESH_SCHEDULES,
    REFRESH_SEGMENTS,
}

data class NotificationJobRunDto(
    val job: NotificationJob,
    val startedAt: Instant,
    val durationMs: Long,
)
