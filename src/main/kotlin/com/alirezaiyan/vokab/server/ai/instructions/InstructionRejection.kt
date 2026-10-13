package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.shared.ApiErrorCode

/**
 * Why the guard refused a learner instruction. [code] is what the client sees: injection attempts
 * answer as off-topic, so probing the guard teaches nothing about what was detected.
 */
enum class InstructionRejection(val code: String) {
    OFF_TOPIC(ApiErrorCode.INSTRUCTIONS_OFF_TOPIC),
    UNSAFE(ApiErrorCode.INSTRUCTIONS_UNSAFE),
    INJECTION(ApiErrorCode.INSTRUCTIONS_OFF_TOPIC),
    PERSONAL_DATA(ApiErrorCode.INSTRUCTIONS_PERSONAL_DATA),
}

/** A learner instruction the guard refused. Mapped to 422; nothing was charged. */
class InstructionRejectedException(val reason: InstructionRejection) :
    RuntimeException("Instructions rejected: $reason")

/** Too many refused instructions lately; instructions are paused for [retryAfterSeconds]. Mapped to 429. */
class InstructionsPausedException(val retryAfterSeconds: Long) :
    RuntimeException("Instructions paused for ${retryAfterSeconds}s after repeated rejections")

/** The guard could not judge the instruction (judge model down or unreadable). Mapped to 503; fails closed. */
class InstructionsUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
