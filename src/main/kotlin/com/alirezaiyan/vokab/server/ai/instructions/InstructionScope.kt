package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.credits.CreditAction

/**
 * An AI feature that takes learner instructions: what the judge may allow there ([description])
 * and what a request costs with and without instructions.
 */
enum class InstructionScope(
    val description: String,
    private val plainAction: CreditAction,
    private val guidedAction: CreditAction,
) {
    PHOTO_EXTRACTION(
        description = "Reading vocabulary from a photo the learner took. The request may say which items in the " +
            "photo to take or skip, and how to annotate them.",
        plainAction = CreditAction.PHOTO_EXTRACTION,
        guidedAction = CreditAction.PHOTO_EXTRACTION_GUIDED,
    ),
    VOCABULARY_SUGGESTION(
        description = "Generating a fresh list of vocabulary for the learner. The request describes what the " +
            "vocabulary should be about: a topic, situation, profession, exam, word type or register.",
        plainAction = CreditAction.AI_SUGGESTION,
        guidedAction = CreditAction.AI_SUGGESTION_GUIDED,
    ),
    ;

    /** What a request is charged: instructions ([guided]) are priced separately. */
    fun creditAction(guided: Boolean): CreditAction = if (guided) guidedAction else plainAction
}
