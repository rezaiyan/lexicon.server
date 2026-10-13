package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Screens a learner's free-text instruction before any credit is spent:
 * prescreen (free, deterministic) → judge model (classify + rewrite), bounded by
 * `app.ai-instructions.judge-timeout-seconds`. Every refusal counts against a per-user window; a
 * user who keeps getting refused is paused for a while, which caps what probing the guard costs.
 *
 * Instruction text is never logged, only its length and the outcome.
 */
@Component
class InstructionGuard(
    private val judge: InstructionJudge,
    private val rateLimitConfig: RateLimitConfig,
    private val appProperties: AppProperties,
) {
    // Judge calls block on I/O; virtual threads keep a slow judge from holding a platform thread
    private val judgeExecutor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * The rewritten intent (and any item count) to hand to the generation prompt.
     *
     * @throws InstructionsPausedException when the user was refused too often lately
     * @throws InstructionRejectedException when the instruction is refused
     * @throws InstructionsUnavailableException when the judge could not decide in time
     */
    fun screen(
        userId: Long,
        instruction: UserInstruction,
        scope: InstructionScope,
        learningLanguage: String,
        nativeLanguage: String,
    ): ScreenedInstruction {
        val rejections = rateLimitConfig.getInstructionRejectionBucket(userId)
        if (rejections.availableTokens <= 0) {
            val waitNanos = rejections.estimateAbilityToConsume(1).nanosToWaitForRefill
            throw InstructionsPausedException(maxOf(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos)))
        }

        val reason = InstructionPrescreen.check(instruction)
            ?: when (val verdict = judgeInTime(instruction, scope, learningLanguage, nativeLanguage)) {
                is InstructionVerdict.Allowed -> {
                    logger.info {
                        "userId=$userId instruction allowed: scope=$scope, length=${instruction.text.length}"
                    }
                    return verdict.instruction
                }
                is InstructionVerdict.Refused -> verdict.reason
            }

        rejections.tryConsume(1)
        logger.info {
            "userId=$userId instruction refused: scope=$scope, reason=$reason, length=${instruction.text.length}"
        }
        throw InstructionRejectedException(reason)
    }

    private fun judgeInTime(
        instruction: UserInstruction,
        scope: InstructionScope,
        learningLanguage: String,
        nativeLanguage: String,
    ): InstructionVerdict {
        val call = CompletableFuture.supplyAsync(
            { judge.judge(instruction, scope, learningLanguage, nativeLanguage) },
            judgeExecutor,
        )
        return try {
            call.get(appProperties.aiInstructions.judgeTimeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // The learner stops waiting here; the HTTP call itself ends on the client's own timeout
            call.cancel(true)
            throw InstructionsUnavailableException("Instruction judge timed out", e)
        } catch (e: ExecutionException) {
            // Whatever went wrong (judge down, unreadable answer, a bug), the guard fails closed
            throw InstructionsUnavailableException("Instruction judge failed", e)
        }
    }

    @PreDestroy
    fun close() = judgeExecutor.close()
}
