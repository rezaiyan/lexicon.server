package com.alirezaiyan.vokab.server.wordrush

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.*

data class SyncWordRushRequest(
    @field:Valid
    @field:NotEmpty
    @field:Size(max = 100, message = "Maximum 100 games per sync request")
    val games: List<SyncWordRushGameRequest>,
)

/** Upper bounds for a single game; generous, but they keep stored stats from being arbitrary. */
private const val MAX_SCORE = 1_000_000L
private const val MAX_QUESTIONS = 10_000L
private const val MAX_LIVES = 100L
private const val MAX_GAME_DURATION_MS = 24L * 60 * 60 * 1000

data class SyncWordRushGameRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val clientGameId: String,
    @field:Min(0)
    @field:Max(MAX_SCORE)
    val score: Int,
    @field:Min(0)
    @field:Max(MAX_QUESTIONS)
    val totalQuestions: Int,
    @field:Min(0)
    @field:Max(MAX_QUESTIONS)
    val correctCount: Int,
    @field:Min(0)
    @field:Max(MAX_QUESTIONS)
    val bestStreak: Int,
    @field:Min(0)
    @field:Max(MAX_GAME_DURATION_MS)
    val durationMs: Long,
    @field:Min(0)
    @field:Max(MAX_GAME_DURATION_MS)
    val avgResponseMs: Long,
    @field:NotBlank
    @field:Size(max = 4)
    val grade: String,
    @field:Min(0)
    @field:Max(MAX_LIVES)
    val livesRemaining: Int,
    val completedNormally: Boolean,
    @field:Min(0)
    val playedAt: Long,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "correctCount and bestStreak cannot exceed totalQuestions")
    val isWithinQuestionCount: Boolean
        get() = correctCount <= totalQuestions && bestStreak <= totalQuestions
}

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
