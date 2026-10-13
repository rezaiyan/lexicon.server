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
import java.util.Locale

private val logger = KotlinLogging.logger {}

/** A rescue's words and copy before it is stored; [candidatesJson] holds every candidate with its violations. */
data class RescueDraft(
    val wordIds: List<Long>,
    val copy: RescueCopy,
    val source: RescueSource,
    val candidatesJson: String? = null,
    val reasoningJson: String? = null,
)

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

        val daysAway = lapseDaysAway(user, today) ?: return null
        val draft = draft(user, daysAway) ?: return null
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

    /** Days since the last study day when the learner is in an early, not yet rescued lapse; else null. */
    private fun lapseDaysAway(user: User, today: LocalDate): Long? {
        val studyDates = dailyActivityRepository
            .findRecentActivities(user, today.minusDays(ComebackRescuePolicy.LOOKBACK_DAYS))
            .map { it.activityDate }
        val lastStudy = studyDates.maxOrNull() ?: return null
        val alreadyRescued = rescueRepository.existsByUserIdAndPlannedForAfter(user.requireId(), lastStudy)
        val signals = LapseSignals(studyDates, candidateQueries.totalWords(user.requireId()), alreadyRescued)
        return ComebackRescuePolicy.daysAway(studyDates, today)
            .takeIf { ComebackRescuePolicy.isEligible(signals, today) }
    }

    /**
     * Words and copy for [user] as if they had been away [daysAway] days, without checking the lapse
     * or storing anything. Null when the learner has too few usable words.
     */
    fun draft(user: User, daysAway: Long): RescueDraft? {
        val userId = user.requireId()
        val pool = RescuePool.from(candidateQueries.candidates(userId), clock.millis())
            ?.takeIf { it.canPlan } ?: return null
        val profile = RescueProfile(
            daysAway = daysAway,
            totalWords = candidateQueries.totalWords(userId),
            longestStreak = user.longestStreak,
            firstWord = candidateQueries.firstWord(userId, pool.sourceLanguage, pool.targetLanguage),
        )
        val sheet = RescueFactSheetBuilder.build(profile, pool)
        val recent = rescueRepository.findTop3ByUserIdOrderByPlannedForDesc(userId)
        val uiLanguage = userSettingsRepository.findByUserId(userId)?.languageCode ?: DEFAULT_UI_LANGUAGE

        return aiDraft(userId, pool, sheet, recent, uiLanguage) ?: fallbackDraft(pool, uiLanguage)
    }

    private fun aiDraft(
        userId: Long,
        pool: RescuePool,
        sheet: RescueFactSheet,
        recent: List<ComebackRescue>,
        uiLanguage: String,
    ): RescueDraft? {
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
        val pickedSheet = sheet.withSessionSize(picks.size)
        val ctx = RescueCopyContext(pickedSheet, sessionWords, lastAngle, recent.map { "${it.title} ${it.body}" })
        val linted = RescueCopyLinter.lintAll(response.candidates, ctx)
        val best = RescueCopyLinter.best(linted)?.candidate

        val copy = if (best != null) {
            RescueCopy(
                angle = best.angle,
                title = best.title,
                body = best.body,
                outroWin = response.outroWin?.takeIf { RescueCopyLinter.outroOk(it, pickedSheet) },
                outroSoft = response.outroSoft?.takeIf { RescueCopyLinter.outroOk(it, pickedSheet) },
            )
        } else {
            templateCopy(pool, picks, uiLanguage)
        }

        return copy?.let {
            RescueDraft(
                wordIds = picks,
                copy = it,
                source = if (best != null) RescueSource.AI else RescueSource.AI_PICKS_TEMPLATE_COPY,
                candidatesJson = objectMapper.writeValueAsString(
                    linted.map { l -> mapOf("candidate" to l.candidate, "violations" to l.violations) }
                ),
                reasoningJson = objectMapper.writeValueAsString(response.reasons),
            )
        }
    }

    private fun fallbackDraft(pool: RescuePool, uiLanguage: String): RescueDraft? {
        val picks = RescueFallback.picks(pool)
        return templateCopy(pool, picks, uiLanguage)?.let { RescueDraft(picks, it, RescueSource.FALLBACK) }
    }

    /** The template copy is English; a learner using the app in another language gets no rescue rather than that. */
    private fun templateCopy(pool: RescuePool, picks: List<Long>, uiLanguage: String): RescueCopy? =
        RescueFallback.copy(pool, picks).takeIf { uiLanguage == DEFAULT_UI_LANGUAGE }

    private fun prompt(pool: RescuePool, sheet: RescueFactSheet, lastAngle: String?, uiLanguage: String): String =
        promptTemplates.render(
            Prompt.COMEBACK_RESCUE,
            mapOf(
                "uiLanguage" to uiLanguage,
                "targetLanguage" to pool.targetLanguage,
                "sessionSize" to RescueFactSheetBuilder.sessionSize(pool),
                "avoidAngle" to (lastAngle ?: "none"),
                "facts" to sheet.render(),
                "nearMastery" to pool.nearMastery.joinToString("\n", transform = ::candidateLine),
                "weak" to pool.weak.joinToString("\n", transform = ::candidateLine),
            ),
        )

    private fun candidateLine(r: RescueCandidateRow): String =
        "id=${r.wordId} \"${r.text}\" (${r.translation}) level=${r.level} recent=${r.recent} " +
            "errorRate=${"%.2f".format(Locale.ROOT, r.errorRate)}"

    companion object {
        /** Learners whose id ends in this digit (10%) form the holdout group: planned, never sent. */
        const val HOLDOUT_BUCKET = 7
        private const val DEFAULT_UI_LANGUAGE = "en"

        fun isHoldout(userId: Long): Boolean = Math.floorMod(userId, 10L) == HOLDOUT_BUCKET.toLong()
    }
}
