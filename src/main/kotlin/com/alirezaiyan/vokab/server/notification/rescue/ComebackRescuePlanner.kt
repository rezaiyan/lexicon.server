package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Builds today's comeback rescue for one learner: checks eligibility, gathers the candidate pool
 * and fact sheet, asks the model for picks and copy, validates both and falls back where needed,
 * then stores the plan. Called from the nightly batch; the AI call runs outside any transaction.
 */
@Service
class ComebackRescuePlanner(
    private val rescueRepository: ComebackRescueRepository,
    private val candidateQueries: RescueCandidateQueries,
    private val dailyActivityRepository: DailyActivityRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val aiClient: AiClient,
    private val promptTemplates: PromptTemplates,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** The stored plan, or null when the learner is not eligible or has too few usable words. */
    fun planIfEligible(user: User): ComebackRescue? {
        val userId = user.requireId()
        val today = LocalDate.now(clock)
        if (rescueRepository.findByUserIdAndPlannedFor(userId, today) != null) return null

        val studyDates = dailyActivityRepository
            .findRecentActivities(user, today.minusDays(ComebackRescuePolicy.LOOKBACK_DAYS))
            .map { it.activityDate }
        val lastStudy = studyDates.maxOrNull() ?: return null
        val totalWords = candidateQueries.totalWords(userId)
        val alreadyRescued = rescueRepository.existsByUserIdAndPlannedForAfterAndStatusIn(
            userId, lastStudy, RescueStatus.CONSUMES_LAPSE,
        )
        if (!ComebackRescuePolicy.isEligible(LapseSignals(studyDates, totalWords, alreadyRescued), today)) return null

        val pool = RescuePool.from(candidateQueries.candidates(userId), clock.millis())
            ?.takeIf { it.canPlan } ?: return null
        val profile = RescueProfile(
            daysAway = requireNotNull(ComebackRescuePolicy.daysAway(studyDates, today)),
            totalWords = totalWords,
            longestStreak = user.longestStreak,
            firstWord = candidateQueries.firstWord(userId, pool.sourceLanguage, pool.targetLanguage),
        )
        val sheet = RescueFactSheetBuilder.build(profile, pool)
        val recent = rescueRepository.findTop3ByUserIdOrderByPlannedForDesc(userId)
        val uiLanguage = userSettingsRepository.findByUserId(userId)?.languageCode ?: DEFAULT_UI_LANGUAGE

        val draft = aiDraft(userId, pool, sheet, recent, uiLanguage) ?: fallbackDraft(pool)
        val status = if (isHoldout(userId)) RescueStatus.HOLDOUT else RescueStatus.PLANNED

        return rescueRepository.save(
            ComebackRescue(
                userId = userId,
                plannedFor = today,
                wordIds = ComebackRescue.encodeWordIds(draft.wordIds),
                title = draft.copy.title,
                body = draft.copy.body,
                angle = draft.copy.angle,
                outroWin = draft.copy.outroWin,
                outroSoft = draft.copy.outroSoft,
                candidates = draft.candidatesJson,
                picksReasoning = draft.reasoningJson,
                source = draft.source,
                status = status,
            )
        ).also { logger.info { "Comeback rescue planned for user=$userId source=${it.source} status=${it.status}" } }
    }

    private data class Draft(
        val wordIds: List<Long>,
        val copy: RescueCopy,
        val source: RescueSource,
        val candidatesJson: String? = null,
        val reasoningJson: String? = null,
    )

    private fun aiDraft(
        userId: Long,
        pool: RescuePool,
        sheet: RescueFactSheet,
        recent: List<ComebackRescue>,
        uiLanguage: String,
    ): Draft? {
        val lastAngle = recent.firstOrNull()?.angle
        val raw = try {
            aiClient.complete(prompt(pool, sheet, lastAngle, uiLanguage), AiOperation.COMEBACK_RESCUE)
        } catch (e: UpstreamServiceException) {
            logger.warn { "Comeback rescue AI unavailable for user=$userId: ${e.message}" }
            null
        } ?: return null
        val response = RescueAiResponse.parse(raw, objectMapper) ?: return null
        val picks = RescuePlanValidator.validPicks(response.wordIds, pool) ?: run {
            logger.warn { "Comeback rescue AI picks rejected for user=$userId: ${response.wordIds}" }
            return null
        }

        val sessionWords = pool.all.filter { it.wordId in picks }.map { it.text }.toSet()
        val ctx = RescueCopyContext(sheet, sessionWords, lastAngle, recent.map { "${it.title} ${it.body}" })
        val linted = RescueCopyLinter.lintAll(response.candidates, ctx)
        val best = RescueCopyLinter.best(linted)?.candidate

        val copy = if (best != null) {
            RescueCopy(
                angle = best.angle,
                title = best.title,
                body = best.body,
                outroWin = response.outroWin?.takeIf { RescueCopyLinter.outroOk(it, sheet) },
                outroSoft = response.outroSoft?.takeIf { RescueCopyLinter.outroOk(it, sheet) },
            )
        } else {
            RescueFallback.copy(pool, picks)
        }

        return Draft(
            wordIds = picks,
            copy = copy,
            source = if (best != null) RescueSource.AI else RescueSource.AI_PICKS_TEMPLATE_COPY,
            candidatesJson = objectMapper.writeValueAsString(
                linted.map { mapOf("candidate" to it.candidate, "violations" to it.violations) }
            ),
            reasoningJson = objectMapper.writeValueAsString(response.reasons),
        )
    }

    private fun fallbackDraft(pool: RescuePool): Draft {
        val picks = RescueFallback.picks(pool)
        return Draft(picks, RescueFallback.copy(pool, picks), RescueSource.FALLBACK)
    }

    private fun prompt(pool: RescuePool, sheet: RescueFactSheet, lastAngle: String?, uiLanguage: String): String =
        promptTemplates.render(
            Prompt.COMEBACK_RESCUE,
            mapOf(
                "uiLanguage" to uiLanguage,
                "targetLanguage" to pool.targetLanguage,
                "sessionSize" to RescueFactSheetBuilder.SESSION_SIZE,
                "avoidAngle" to (lastAngle ?: "none"),
                "facts" to sheet.render(),
                "nearMastery" to pool.nearMastery.joinToString("\n", transform = ::candidateLine),
                "weak" to pool.weak.joinToString("\n", transform = ::candidateLine),
            ),
        )

    private fun candidateLine(r: RescueCandidateRow): String =
        "id=${r.wordId} \"${r.text}\" (${r.translation}) level=${r.level} recent=${r.recent} " +
            "errorRate=${"%.2f".format(r.errorRate)}"

    companion object {
        /** Learners whose id ends in this digit (10%) form the holdout group: planned, never sent. */
        const val HOLDOUT_BUCKET = 7
        private const val DEFAULT_UI_LANGUAGE = "en"

        fun isHoldout(userId: Long): Boolean = Math.floorMod(userId, 10L) == HOLDOUT_BUCKET.toLong()
    }
}
