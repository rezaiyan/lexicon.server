package com.alirezaiyan.vokab.server.notification.rescue

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

/** One AI copy candidate as stored on a plan or preview: the copy, the linter's and the judge's verdicts. */
data class StoredCandidate(
    val candidate: RescueCopyCandidate,
    val violations: List<String>,
    /** Null when the judge gave no answer for it (or the plan predates the judge). */
    val judge: JudgeVerdict? = null,
) {
    /** Could have been sent: clean lint and not rejected by the judge. */
    val approved: Boolean get() = violations.isEmpty() && judge?.pass != false
}

/** Reads the `candidates` JSON the planner stores (`[{"candidate": {...}, "violations": [...]}]`). */
fun ObjectMapper.readStoredCandidates(json: String?): List<StoredCandidate> {
    if (json.isNullOrBlank()) return emptyList()
    return readTree(json).map { node ->
        val c = node.path("candidate")
        StoredCandidate(
            candidate = RescueCopyCandidate(
                angle = c.path("angle").asText(),
                title = c.path("title").asText(),
                body = c.path("body").asText(),
                factIds = c.path("factIds").map { it.asText() },
                rank = c.path("rank").asInt(Int.MAX_VALUE),
            ),
            violations = node.path("violations").map { it.asText() },
            judge = node.path("judge").takeIf { it.isObject }?.let {
                JudgeVerdict(it.path("pass").asBoolean(false), it.path("reason").asText())
            },
        )
    }
}

data class RescueCandidateView(
    val index: Int,
    val angle: String,
    val title: String,
    val body: String,
    val factIds: List<String>,
    val rank: Int,
    val violations: List<String>,
    val judgePass: Boolean?,
    val judgeReason: String?,
    /** The candidate the learner would have seen. */
    val chosen: Boolean,
    val rating: CopyRating?,
    val note: String?,
)

data class RescuePreviewView(
    val id: Long,
    val userId: Long,
    val createdAt: Instant,
    val promptVersion: String,
    val source: RescueSource,
    val angle: String,
    val title: String,
    val body: String,
    val outroWin: String?,
    val outroSoft: String?,
    val wordIds: List<Long>,
    val candidates: List<RescueCandidateView>,
)

data class RescuePlanView(
    val id: Long,
    val userId: Long,
    val plannedFor: LocalDate,
    val status: RescueStatus,
    val source: RescueSource,
    val angle: String,
    val title: String,
    val body: String,
    val outroWin: String?,
    val outroSoft: String?,
    val wordCount: Int,
    val sentAt: Instant?,
    val completedAt: Instant?,
)

data class RescueRatingRequest(
    @field:Min(0)
    val candidateIndex: Int,
    val rating: CopyRating,
    @field:Size(max = 500)
    val note: String? = null,
)

/** One rated candidate in the regression eval set (one JSON line each). */
data class RescueEvalLine(
    val previewId: Long,
    val candidateIndex: Int,
    val promptVersion: String,
    val rating: CopyRating,
    val note: String?,
    val chosen: Boolean,
    val angle: String,
    val title: String,
    val body: String,
    val factIds: List<String>,
    val violations: List<String>,
    val judgePass: Boolean?,
    val judgeReason: String?,
)
