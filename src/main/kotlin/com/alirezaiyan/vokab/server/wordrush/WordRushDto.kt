package com.alirezaiyan.vokab.server.wordrush

import jakarta.validation.Valid
import jakarta.validation.constraints.*

data class SyncWordRushRequest(
    @field:Valid
    @field:NotEmpty
    @field:Size(max = 100, message = "Maximum 100 games per sync request")
    val games: List<SyncWordRushGameRequest>,
)

data class SyncWordRushGameRequest(
    @field:NotBlank
    val clientGameId: String,
    @field:Min(0)
    val score: Int,
    @field:Min(0)
    val totalQuestions: Int,
    @field:Min(0)
    val correctCount: Int,
    @field:Min(0)
    val bestStreak: Int,
    @field:Min(0)
    val durationMs: Long,
    @field:Min(0)
    val avgResponseMs: Long,
    @field:NotBlank
    val grade: String,
    @field:Min(0)
    val livesRemaining: Int,
    val completedNormally: Boolean,
    val playedAt: Long,
)

data class SyncWordRushResponse(
    val syncedGameIds: List<String>,
)

data class WordRushInsightsResponse(
    val totalGames: Long,
    val totalCompleted: Long,
    val completionRatePercent: Double,
    val bestStreakEver: Int,
    val avgScore: Double,
    val avgAccuracyPercent: Double,
    val totalTimePlayedMs: Long,
    val avgDurationMs: Double,
    val avgResponseMs: Double,
)

data class WordRushGameResponse(
    val clientGameId: String,
    val score: Int,
    val totalQuestions: Int,
    val correctCount: Int,
    val bestStreak: Int,
    val durationMs: Long,
    val avgResponseMs: Long,
    val grade: String,
    val livesRemaining: Int,
    val completedNormally: Boolean,
    val playedAt: Long,
)
