package com.alirezaiyan.vokab.server.analytics

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TrackEventRequest(
    @field:NotBlank(message = "Event name is required")
    @field:Size(max = 100, message = "Event name must not exceed 100 characters")
    val eventName: String,

    val properties: Map<String, String> = emptyMap(),

    @field:Size(max = 20)
    val platform: String? = null,

    @field:Size(max = 30)
    val appVersion: String? = null,

    val clientTimestampMs: Long,
)
