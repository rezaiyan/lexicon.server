package com.alirezaiyan.vokab.server.listening

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class SyncListeningRequest(
    @field:Valid
    @field:NotEmpty
    @field:Size(max = 100, message = "Maximum 100 sessions per sync request")
    val sessions: List<SyncListeningSessionRequest>,
)

data class SyncListeningSessionRequest(
    @field:NotBlank
    val clientSessionId: String,
    /** due | all | level | tag */
    @field:NotBlank
    val source: String,
    /** Stage name for `level`, tag id for `tag`; null otherwise. */
    val sourceDetail: String? = null,
    /** word_first | translation_first */
    @field:NotBlank
    val order: String,
    @field:Min(1)
    val repeatCount: Int,
    @field:Min(0)
    val pauseMs: Long,
    @field:Positive
    val speechRate: Float,
    @field:Min(0)
    val plannedWords: Int,
    @field:Min(0)
    val wordsHeard: Int,
    @field:Min(0)
    val wordsSkipped: Int,
    @field:Min(0)
    val pauseCount: Int,
    /** Time spent actually playing, excluding user pauses. */
    @field:Min(0)
    val listeningMs: Long,
    /** Wall-clock time from start to end. */
    @field:Min(0)
    val durationMs: Long,
    val completedNormally: Boolean,
    val startedAt: Long,
    val endedAt: Long,
    @field:Valid
    @field:Size(max = 1000, message = "Maximum 1000 words per session")
    val words: List<SyncListeningWordRequest> = emptyList(),
)

data class SyncListeningWordRequest(
    val wordId: Long,
    @field:NotBlank
    val sourceLanguage: String,
    @field:NotBlank
    val targetLanguage: String,
    val heardAt: Long,
)

data class SyncListeningResponse(
    val syncedSessionIds: List<String>,
)
