package com.alirezaiyan.vokab.server.ai.instructions

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
