package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.ai.asPromptLabel
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Asks the judge model whether an instruction is a vocabulary request, and if so for its rewritten
 * intent and any item count the learner named. Fails closed: when the judge is down or its answer unreadable, nothing is allowed.
 */
@Component
class InstructionJudge(
    private val aiClient: AiClient,
    private val promptTemplates: PromptTemplates,
    private val objectMapper: ObjectMapper,
) {

    /** @throws InstructionsUnavailableException when the judge gave no usable answer */
    fun judge(
        instruction: UserInstruction,
        scope: InstructionScope,
        learningLanguage: String,
        nativeLanguage: String,
    ): InstructionVerdict {
        val prompt = promptTemplates.render(
            Prompt.INSTRUCTION_GUARD,
            mapOf(
                "scope" to scope.description,
                "learningLanguage" to learningLanguage.asPromptLabel(),
                "nativeLanguage" to nativeLanguage.asPromptLabel(),
                "instruction" to instruction.text,
            ),
        )
        val raw = try {
            aiClient.complete(prompt, AiOperation.INSTRUCTION_GUARD)
        } catch (e: UpstreamServiceException) {
            throw InstructionsUnavailableException("Instruction judge unavailable", e)
        }
        return raw?.let(::parse) ?: throw InstructionsUnavailableException("No usable instruction judge answer")
    }

    /**
     * The model sometimes answers, then corrects itself in a second JSON object ("Wait, let me
     * reconsider…"). The last answer counts, unless the answers disagree on the verdict.
     */
    private fun parse(raw: String): InstructionVerdict? {
        val answers = jsonObjects(raw)
            .mapNotNull { runCatching { objectMapper.readTree(it) }.getOrNull() }
            .filter { it.path("verdict").isString }
        if (answers.isEmpty()) {
            logger.warn { "Instruction judge answer had no readable verdict: length=${raw.length}" }
            return null
        }
        if (answers.map { it.path("verdict").asString() }.distinct().size > 1) {
            logger.warn { "Instruction judge changed its verdict within one answer: answers=${answers.size}" }
            return null
        }
        val node = answers.last()
        return when (node.path("verdict").asString()) {
            "ALLOW" -> sanitizeIntent(node.path("intent").asString())?.let { intent ->
                InstructionVerdict.Allowed(ScreenedInstruction(intent, itemCount(node.path("count"))))
            }
            "OFF_TOPIC" -> InstructionVerdict.Refused(InstructionRejection.OFF_TOPIC)
            "UNSAFE" -> InstructionVerdict.Refused(InstructionRejection.UNSAFE)
            "INJECTION" -> InstructionVerdict.Refused(InstructionRejection.INJECTION)
            else -> {
                logger.warn { "Instruction judge answered an unknown verdict" }
                null
            }
        }
    }

    /** Each top-level `{…}` in [raw], in order; braces inside JSON strings don't count. */
    private fun jsonObjects(raw: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        raw.forEachIndexed { i, c ->
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' && depth > 0 -> inString = !inString
                inString -> Unit
                c == '{' -> if (depth++ == 0) start = i
                c == '}' && depth > 0 -> if (--depth == 0) objects += raw.substring(start, i + 1)
            }
        }
        return objects
    }

    /** The intent ends up inside a fenced prompt block, so nothing that could close or open one survives. */
    private fun sanitizeIntent(intent: String): ScreenedIntent? {
        val cleaned = intent.filterNot { it in FENCE_CHARS }
        val normalized = runCatching { UserInstruction.of(cleaned.take(MAX_INTENT_LENGTH)) }.getOrNull()
        return normalized?.let { ScreenedIntent(it.text) }
    }

    /** A whole, positive number of items, or null; the caller caps it at what the feature allows. */
    private fun itemCount(node: JsonNode): Int? =
        node.takeIf { it.isIntegralNumber }?.asInt()?.takeIf { it in 1..MAX_ITEM_COUNT }

    private companion object {
        const val MAX_INTENT_LENGTH = 200
        const val MAX_ITEM_COUNT = 500
        val FENCE_CHARS = setOf('<', '>', '{', '}', '`')
    }
}
