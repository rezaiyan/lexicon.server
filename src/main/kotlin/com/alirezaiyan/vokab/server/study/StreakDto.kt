package com.alirezaiyan.vokab.server.study

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** Most reviews one activity record may add (matches the 1000-word sync batch). */
const val MAX_REVIEWS_PER_RECORD = 1000

data class StreakResponse(
    val currentStreak: Int
)

data class RecordActivityRequest(
    /** Words reviewed since the last record. */
    @field:Min(0)
    @field:Max(MAX_REVIEWS_PER_RECORD.toLong())
    val count: Int = 1
)
