package com.alirezaiyan.vokab.server.ai

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.Size

/** Kinds of vocabulary item a learner can ask for. */
enum class WordType(val promptName: String) {
    NOUN("nouns"),
    VERB("verbs"),
    ADJECTIVE("adjectives"),
    ADVERB("adverbs"),
    PHRASE("short phrases"),
    IDIOM("idioms and fixed expressions"),
}

/** The register (tone) of the vocabulary. */
enum class Register(val promptLine: String) {
    FORMAL("Prefer formal, polite vocabulary suited to work and official situations."),
    EVERYDAY("Prefer everyday, conversational vocabulary."),
    SLANG("Include common informal and slang expressions a learner should recognize; nothing offensive."),
}

/** What goes in each item's note; none picked leaves it a brief hint, as the base prompt asks. */
enum class NoteStyle(val promptLine: (noteField: String) -> String) {
    EXAMPLE({ "In $it, give one short example sentence using the item, on one line." }),
    GRAMMAR({ "In $it, give the grammar: article and plural for nouns, key forms for verbs." }),
}

/**
 * The learner's quick picks (chips in the app). Every option maps to a sentence written here, so
 * none of it is learner text: quick picks are free and need no screening, unlike instructions.
 * Options that don't apply to a feature are simply not sent by the app.
 */
data class VocabularyFocus(
    @field:Size(max = 6)
    val wordTypes: Set<WordType> = emptySet(),
    val register: Register? = null,
    /** Older apps: put grammar in the note. Newer apps send [noteStyle], which wins. */
    val grammarNotes: Boolean = false,
    val noteStyle: NoteStyle? = null,
    /** Photo only: take only items marked in the photo (highlighted, underlined, circled). */
    val markedOnly: Boolean = false,
    /** Photo only: skip names of people, places and brands, and numbers. */
    val skipNames: Boolean = false,
) {
    @get:JsonIgnore
    val effectiveNoteStyle: NoteStyle?
        get() = noteStyle ?: NoteStyle.GRAMMAR.takeIf { grammarNotes }

    /**
     * The prompt block for these picks; empty when nothing is picked. [noteField] is what the
     * prompt's output format calls the note.
     */
    fun toPromptBlock(noteField: String): String {
        val lines = buildList {
            if (wordTypes.isNotEmpty()) {
                add("Only include these kinds of items: ${wordTypes.sorted().joinToString { it.promptName }}.")
            }
            register?.let { add(it.promptLine) }
            effectiveNoteStyle?.let { add(it.promptLine(noteField)) }
            if (markedOnly) {
                add(
                    "Only take items that are visibly marked (highlighted, underlined or circled). " +
                        "If nothing is marked, return no items.",
                )
            }
            if (skipNames) add("Skip names of people, places and brands, and skip numbers.")
        }
        if (lines.isEmpty()) return ""
        return "Learner focus (these override any balance or mix asked for above):\n" +
            lines.joinToString("\n") { "- $it" }
    }
}
