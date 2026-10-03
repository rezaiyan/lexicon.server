package com.alirezaiyan.vokab.server.notification

import com.fasterxml.jackson.annotation.JsonProperty

data class NotificationAdminStatsDto(
    val totalActiveSchedules: Long,
    val suppressedUsers: SuppressedUsersDto,
    val last7Days: Last7DaysDto
)

data class SuppressedUsersDto(
    @JsonProperty("3day") val day3: Long,
    @JsonProperty("7day") val day7: Long,
    @JsonProperty("14day") val day14: Long,
    @JsonProperty("30day") val day30: Long
)

data class Last7DaysDto(
    val totalSent: Long,
    val totalOpened: Long,
    val openRatePercent: Int,
    val typeBreakdown: Map<String, TypeStatsDto>
)

data class TypeStatsDto(
    val sent: Long,
    val opened: Long,
    val openRate: Int
)
