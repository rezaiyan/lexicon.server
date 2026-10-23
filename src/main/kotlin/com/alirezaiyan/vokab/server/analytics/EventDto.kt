package com.alirezaiyan.vokab.server.analytics

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Limits on [TrackEventRequest.properties], which is stored as JSON on every event row. */
const val MAX_EVENT_PROPERTIES = 50
const val MAX_EVENT_PROPERTY_KEY_LENGTH = 100
const val MAX_EVENT_PROPERTY_VALUE_LENGTH = 1000

data class TrackEventRequest(
    @field:NotBlank(message = "Event name is required")
    @field:Size(max = 100, message = "Event name must not exceed 100 characters")
    val eventName: String,

    @field:Size(max = MAX_EVENT_PROPERTIES, message = "Too many event properties")
    val properties: Map<String, String> = emptyMap(),

    @field:Size(max = 20)
    val platform: String? = null,

    @field:Size(max = 30)
    val appVersion: String? = null,

    val clientTimestampMs: Long,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "Event property key or value too long")
    val isPropertiesWithinLimits: Boolean
        get() = properties.all { (key, value) ->
            key.length <= MAX_EVENT_PROPERTY_KEY_LENGTH && value.length <= MAX_EVENT_PROPERTY_VALUE_LENGTH
        }
}
