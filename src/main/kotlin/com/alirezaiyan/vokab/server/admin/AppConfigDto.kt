package com.alirezaiyan.vokab.server.admin

import jakarta.validation.constraints.NotNull
import java.time.Instant

data class AppConfigDto(
    val namespace: String,
    val key: String,
    val value: String?,
    val type: String,
    val description: String?,
    val enabled: Boolean,
    val updatedAt: Instant
)

data class AppConfigHistoryDto(
    val oldValue: String?,
    val newValue: String?,
    val changedBy: String?,
    val changedAt: Instant
)

data class AppConfigUpdateRequest(
    @field:NotNull val value: String
)

data class AppConfigListItemRequest(
    @field:NotNull val item: String
)

fun AppConfig.toDto() = AppConfigDto(
    namespace = namespace,
    key = key,
    value = value,
    type = type,
    description = description,
    enabled = enabled,
    updatedAt = updatedAt
)

fun AppConfigHistory.toDto() = AppConfigHistoryDto(
    oldValue = oldValue,
    newValue = newValue,
    changedBy = changedBy,
    changedAt = changedAt
)
