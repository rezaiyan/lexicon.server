package com.alirezaiyan.vokab.server.notification.rescue

import java.text.BreakIterator

data class RescueCopyContext(
    val factSheet: RescueFactSheet,
    /** Target-language text of the words in this session. */
    val sessionWords: Set<String>,
    val lastAngle: String?,
    /** "title body" of the learner's recent rescues. */
    val recentCopies: List<String>,
)

data class LintedCandidate(val candidate: RescueCopyCandidate, val violations: List<String>) {
    val ok: Boolean get() = violations.isEmpty()
}

/**
 * Hard rules every rescue copy must pass: grounded in the fact sheet, names a session word, short,
 * and free of the hype, guilt and tics that make a push read as machine-written.
 */
object RescueCopyLinter {
    const val TITLE_MAX = 40
    const val BODY_MAX = 110
    const val OUTRO_MAX = 90

    val ANGLES = setOf("rematch", "almost_there", "callback", "progress_fact", "curiosity")

    val BANNED_PHRASES = listOf(
        "journey", "unlock", "crush", "level up", "boost", "supercharge",
        "don't lose", "don't break", "we miss you", "ready to", "let's go", "hey there", "oops",
    )

    private val NUMBER = Regex("""\d+""")
    private val QUOTED = Regex("""["“”«»]([^"“”«»]{1,40})["“”«»]""")

    fun lintAll(candidates: List<RescueCopyCandidate>, ctx: RescueCopyContext): List<LintedCandidate> =
        candidates.map { LintedCandidate(it, lint(it, ctx)) }

    /** The model's highest-ranked candidate that passed every rule, or null. */
    fun best(linted: List<LintedCandidate>): LintedCandidate? =
        linted.filter { it.ok }.minByOrNull { it.candidate.rank }

    fun lint(c: RescueCopyCandidate, ctx: RescueCopyContext): List<String> {
        val v = mutableListOf<String>()
        val text = "${c.title} ${c.body}"

        if (c.angle !in ANGLES) v += "unknown angle ${c.angle}"
        if (c.angle == ctx.lastAngle) v += "repeats last angle"
        if (c.title.isBlank() || graphemes(c.title) > TITLE_MAX) v += "title length"
        if (c.body.isBlank() || graphemes(c.body) > BODY_MAX) v += "body length"
        if (ctx.sessionWords.none { text.contains(it, ignoreCase = true) }) v += "names no session word"

        val unknownFacts = c.factIds.filter { id -> ctx.factSheet.facts.none { it.id == id } }
        unknownFacts.forEach { v += "cites unknown fact $it" }
        val citedNumbers = ctx.factSheet.byIds(c.factIds).flatMap { NUMBER.findAll(it.text).map { m -> m.value }.toList() }.toSet()
        NUMBER.findAll(withoutWords(text, ctx.factSheet.allWords + ctx.sessionWords)).map { it.value }.distinct()
            .filter { it !in citedNumbers }.forEach { v += "ungrounded number $it" }
        QUOTED.findAll(text).map { it.groupValues[1] }.filter { it !in ctx.factSheet.allWords }.forEach { v += "ungrounded word $it" }

        v += toneViolations(text)

        val normalized = normalize(text)
        if (ctx.recentCopies.any { normalize(it) == normalized }) v += "repeats a recent copy"

        return v
    }

    /** An outro may be shown only if it is short, calm and invents no numbers. */
    fun outroOk(outro: String?, sheet: RescueFactSheet): Boolean {
        if (outro.isNullOrBlank() || graphemes(outro) > OUTRO_MAX) return false
        val knownNumbers = sheet.facts.flatMap { NUMBER.findAll(it.text).map { m -> m.value }.toList() }.toSet()
        if (NUMBER.findAll(withoutWords(outro, sheet.allWords)).any { it.value !in knownNumbers }) return false
        return toneViolations(outro).isEmpty()
    }

    /** [text] with the learner's own words blanked out, so a word like "B2" is not read as a stat. */
    private fun withoutWords(text: String, words: Set<String>): String =
        words.sortedByDescending { it.length }.fold(text) { acc, word -> acc.replace(word, " ", ignoreCase = true) }

    fun graphemes(s: String): Int {
        val it = BreakIterator.getCharacterInstance().apply { setText(s) }
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    private fun toneViolations(text: String): List<String> {
        val v = mutableListOf<String>()
        val lower = text.lowercase().replace('’', '\'')
        BANNED_PHRASES.filter { it in lower }.forEach { v += "banned phrase $it" }
        if ('—' in text) v += "em dash"
        if (text.count { it == '!' } > 1) v += "too many exclamation marks"
        if (text.codePoints().anyMatch(::isEmoji)) v += "emoji"
        return v
    }

    private fun isEmoji(cp: Int): Boolean = cp >= 0x1F000 || cp in 0x2600..0x27BF

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
