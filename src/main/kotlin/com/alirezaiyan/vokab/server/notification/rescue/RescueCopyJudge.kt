package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/** The judge's call on one copy candidate. */
data class JudgeVerdict(val pass: Boolean, val reason: String)

/**
 * The automatic taste test: a second, stricter model call reviews the copy candidates that passed
 * the linter and approves only those a learner would be glad to receive. Fails closed: when the
 * judge is down or unreadable, nothing is approved and the planner uses its template copy.
 */
@Component
class RescueCopyJudge(
    private val aiClient: AiClient,
    private val promptTemplates: PromptTemplates,
    private val objectMapper: ObjectMapper,
) {
    /** Verdicts by candidate index (a missing index is not approved); null when the judge gave no usable answer. */
    fun judge(
        candidates: List<RescueCopyCandidate>,
        sheet: RescueFactSheet,
        uiLanguage: String,
        targetLanguage: String,
    ): Map<Int, JudgeVerdict>? {
        if (candidates.isEmpty()) return emptyMap()
        val prompt = promptTemplates.render(
            Prompt.COMEBACK_RESCUE_JUDGE,
            mapOf(
                "uiLanguage" to uiLanguage,
                "targetLanguage" to targetLanguage,
                "facts" to sheet.render(),
                "candidates" to candidates.withIndex().joinToString("\n") { (i, c) -> "[$i] ${c.title} | ${c.body}" },
            ),
        )
        val raw = try {
            aiClient.complete(prompt, AiOperation.COMEBACK_RESCUE_JUDGE)
        } catch (e: UpstreamServiceException) {
            logger.warn { "Comeback rescue judge unavailable: ${e.message}" }
            null
        } ?: return null
        return parse(raw, candidates.indices)
    }

    private fun parse(raw: String, indices: IntRange): Map<Int, JudgeVerdict>? {
        val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val verdicts = runCatching { objectMapper.readTree(json).path("verdicts") }.getOrNull()
        if (verdicts == null || !verdicts.isArray) {
            logger.warn { "Unparsable comeback rescue judge response" }
            return null
        }
        return verdicts
            .filter { it.path("index").isInt && it.path("index").asInt() in indices }
            .associate { node ->
                val verdict = JudgeVerdict(node.path("pass").asBoolean(false), node.path("reason").asText())
                node.path("index").asInt() to verdict
            }
    }
}
