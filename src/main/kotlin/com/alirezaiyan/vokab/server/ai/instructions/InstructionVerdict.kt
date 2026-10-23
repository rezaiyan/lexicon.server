package com.alirezaiyan.vokab.server.ai.instructions

/**
 * The guard's rewrite of an allowed instruction: a short, neutral English description of the
 * vocabulary wanted. The only form of learner text a generation prompt ever receives.
 */
@JvmInline
value class ScreenedIntent(val text: String)

/**
 * What an allowed instruction asks for: the rewritten [intent], plus [itemCount] when the learner
 * named how many items they want ("just 5 words"). The count travels as a number, never as text,
 * so the generation prompt gets it from the server and not from the learner's words.
 */
data class ScreenedInstruction(val intent: ScreenedIntent, val itemCount: Int? = null)

/** The judge's decision on one instruction. */
sealed interface InstructionVerdict {
    data class Allowed(val instruction: ScreenedInstruction) : InstructionVerdict
    data class Refused(val reason: InstructionRejection) : InstructionVerdict
}
