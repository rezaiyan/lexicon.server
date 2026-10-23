package com.alirezaiyan.vokab.server.notification.rescue

/** Script-aware text helpers for checking rescue copy in any of the learner's languages. */
internal object RescueText {
    /** Digits in any script (ASCII, Persian, full-width, ...). */
    private val NUMBER = Regex("""\p{Nd}+""")

    /** Scripts written without spaces between words, where a word may touch other letters. */
    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
    )

    /** Every number in [text] in ASCII digits: "۸" and "８" both become "8". */
    fun numbers(text: String): Sequence<String> = NUMBER.findAll(text).map { asciiDigits(it.value) }

    private fun asciiDigits(digits: String): String =
        digits.map { Character.forDigit(Character.digit(it, 10), 10) }.joinToString("")

    /**
     * [word] appears in [text] as a word of its own, so a short word like "in" is not found inside
     * "Training". Letters of scripts without spaces don't count as part of a word, so "Apfel" is
     * found in "Apfelをもう一度" and "猫" in "猫を".
     */
    fun containsWord(text: String, word: String): Boolean =
        occurrences(text, word).any { start ->
            !continuesWord(text, start - 1) && !continuesWord(text, start + word.length)
        }

    /** [stem] starts a word in [text]: "unlock" matches "unlocked" but "ready to" not "already today". */
    fun startsWord(text: String, stem: String): Boolean =
        occurrences(text, stem).any { start -> !continuesWord(text, start - 1) }

    /** [text] with the learner's own words blanked out, so a word like "B2" is not read as a stat. */
    fun withoutWords(text: String, words: Set<String>): String =
        words.sortedByDescending { it.length }.fold(text) { acc, word -> acc.replace(word, " ", ignoreCase = true) }

    private fun occurrences(text: String, word: String): Sequence<Int> {
        val haystack = normalizeApostrophes(text)
        val needle = normalizeApostrophes(word)
        if (needle.isBlank()) return emptySequence()
        return generateSequence(haystack.indexOf(needle, ignoreCase = true)) { previous ->
            haystack.indexOf(needle, previous + 1, ignoreCase = true)
        }.takeWhile { it >= 0 }
    }

    private fun continuesWord(text: String, index: Int): Boolean {
        val c = text.getOrNull(index) ?: return false
        return c.isLetterOrDigit() && Character.UnicodeScript.of(c.code) !in UNSPACED_SCRIPTS
    }

    private fun normalizeApostrophes(s: String): String = s.replace('’', '\'')
}
