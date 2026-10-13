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
     * "Training". In scripts without spaces any occurrence counts.
     */
    fun containsWord(text: String, word: String): Boolean {
        if (word.isBlank()) return false
        if (word.any { Character.UnicodeScript.of(it.code) in UNSPACED_SCRIPTS }) {
            return text.contains(word, ignoreCase = true)
        }
        val pattern = Regex("""(?<![\p{L}\p{N}])${Regex.escape(word)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    /** [text] with the learner's own words blanked out, so a word like "B2" is not read as a stat. */
    fun withoutWords(text: String, words: Set<String>): String =
        words.sortedByDescending { it.length }.fold(text) { acc, word -> acc.replace(word, " ", ignoreCase = true) }
}
