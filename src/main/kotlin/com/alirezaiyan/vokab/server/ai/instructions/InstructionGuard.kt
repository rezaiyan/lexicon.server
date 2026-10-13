package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Screens a learner's free-text instruction before any credit is spent:
 * prescreen (free, deterministic) → judge model (classify + rewrite). Every refusal counts
 * against a per-user window; a user who keeps getting refused is paused for a while, which caps
 * what probing the guard can cost.
 *
 * Instruction text is never logged, only its length and the outcome.
 */
@Component
class InstructionGuard(
    private val judge: InstructionJudge,
    private val rateLimitConfig: RateLimitConfig,
) {

    /**
     * The rewritten intent to hand to the generation prompt.
     *
     * @throws InstructionsPausedException when the user was refused too often lately
     * @throws InstructionRejectedException when the instruction is refused
     * @throws InstructionsUnavailableException when the judge could not decide
     */
    fun screen(
        userId: Long,
        instruction: UserInstruction,
        scope: InstructionScope,
        learningLanguage: String,
        nativeLanguage: String,
    ): ScreenedIntent {
        val rejections = rateLimitConfig.getInstructionRejectionBucket(userId)
        if (rejections.availableTokens <= 0) {
            val waitNanos = rejections.estimateAbilityToConsume(1).nanosToWaitForRefill
            throw InstructionsPausedException(maxOf(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos)))
        }

        val reason = InstructionPrescreen.check(instruction)
            ?: when (val verdict = judge.judge(instruction, scope, learningLanguage, nativeLanguage)) {
                is InstructionVerdict.Allowed -> {
                    logger.info {
                        "userId=$userId instruction allowed: scope=$scope, length=${instruction.text.length}"
                    }
                    return verdict.intent
                }
                is InstructionVerdict.Refused -> verdict.reason
            }

        rejections.tryConsume(1)
        logger.info {
            "userId=$userId instruction refused: scope=$scope, reason=$reason, length=${instruction.text.length}"
        }
        throw InstructionRejectedException(reason)
    }
}
