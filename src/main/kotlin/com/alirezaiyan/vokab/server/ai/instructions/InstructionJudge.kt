package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/** Which AI feature an instruction is for; [description] tells the judge what may be asked there. */
enum class InstructionScope(val description: String) {
    PHOTO_EXTRACTION(
        "Reading vocabulary from a photo the learner took. The request may say which items in the photo " +
            "to take or skip, and how to annotate them.",
    ),
    VOCABULARY_SUGGESTION(
        "Generating a fresh list of vocabulary for the learner. The request describes what the vocabulary " +
            "should be about: a topic, situation, profession, exam, word type or register.",
    ),
}

/**
 * The guard's rewrite of an allowed instruction: a short, neutral English description of the
 * vocabulary wanted. The only form of learner text a generation prompt ever receives.
 */
@JvmInline
value class ScreenedIntent(val text: String)

/** The judge's decision on one instruction. */
sealed interface InstructionVerdict {
    data class Allowed(val intent: ScreenedIntent) : InstructionVerdict
    data class Refused(val reason: InstructionRejection) : InstructionVerdict
}

/**
 * Asks the judge model whether an instruction is a vocabulary request, and if so for its rewritten
 * intent. Fails closed: when the judge is down or its answer unreadable, nothing is allowed.
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
                "learningLanguage" to learningLanguage,
                "nativeLanguage" to nativeLanguage,
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

    private fun parse(raw: String): InstructionVerdict? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val node = runCatching { objectMapper.readTree(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        return when (node.path("verdict").asText()) {
            "ALLOW" -> sanitizeIntent(node.path("intent").asText())?.let { InstructionVerdict.Allowed(it) }
            "OFF_TOPIC" -> InstructionVerdict.Refused(InstructionRejection.OFF_TOPIC)
            "UNSAFE" -> InstructionVerdict.Refused(InstructionRejection.UNSAFE)
            "INJECTION" -> InstructionVerdict.Refused(InstructionRejection.INJECTION)
            else -> {
                logger.warn { "Instruction judge answered an unknown verdict" }
                null
            }
        }
    }

    /** The intent ends up inside a fenced prompt block, so nothing that could close or open one survives. */
    private fun sanitizeIntent(intent: String): ScreenedIntent? {
        val cleaned = intent.filterNot { it in FENCE_CHARS }
        val normalized = runCatching { UserInstruction.of(cleaned.take(MAX_INTENT_LENGTH)) }.getOrNull()
        return normalized?.let { ScreenedIntent(it.text) }
    }

    private companion object {
        const val MAX_INTENT_LENGTH = 200
        val FENCE_CHARS = setOf('<', '>', '{', '}', '`')
    }
}
