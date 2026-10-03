package com.alirezaiyan.vokab.server.user

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Pattern
import java.time.LocalDate

data class SettingsDto(
    val languageCode: String,
    val themeMode: String,
    val notificationsEnabled: Boolean,
    val reviewRemindersEnabled: Boolean = true,
    @field:Pattern(regexp = "^([01]?\\d|2[0-3]):[0-5]\\d$", message = "Must be HH:MM format")
    val dailyReminderTime: String = "18:00",
    @field:Pattern(regexp = "^(DAILY|EVERY_OTHER_DAY|WEEKLY|OFF)$", message = "Must be one of: DAILY, EVERY_OTHER_DAY, WEEKLY, OFF")
    val notificationFrequency: String = "DAILY",
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val optimalSendHour: Int? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val dataConfidence: Int? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val engagementStats: EngagementStatsDto? = null
)

data class EngagementStatsDto(
    val openRatePercent: Int,
    val consecutiveIgnores: Int,
    val suppressedUntil: LocalDate?
)
