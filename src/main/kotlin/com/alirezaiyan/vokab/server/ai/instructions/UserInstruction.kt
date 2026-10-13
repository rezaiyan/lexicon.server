package com.alirezaiyan.vokab.server.ai.instructions

import java.text.Normalizer

/**
 * A learner's free-text request to the AI ("only the highlighted words"), normalized so that
 * what the guard judges is exactly what it sees: NFKC, no control or invisible formatting
 * characters, single spaces, at most [MAX_LENGTH] characters.
 *
 * Untrusted input: it only ever reaches a generation prompt as the guard's rewritten intent.
 */
@JvmInline
value class UserInstruction private constructor(val text: String) {

    companion object {
        const val MAX_LENGTH = 300

        // Zero-width non-joiner is part of Persian spelling, so it survives the format-char strip
        private const val ZWNJ = '‌'
        private val WHITESPACE = Regex("""\s+""")

        /**
         * The normalized instruction; null when [raw] is null or blank after normalization.
         *
         * @throws IllegalArgumentException when longer than [MAX_LENGTH] after normalization
         */
        fun of(raw: String?): UserInstruction? {
            if (raw == null) return null
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .filterNot { it.isInvisibleControl() }
                .replace(WHITESPACE, " ")
                .trim()
            if (text.isEmpty()) return null
            require(text.length <= MAX_LENGTH) { "Instructions are too long (max $MAX_LENGTH characters)" }
            return UserInstruction(text)
        }

        private fun Char.isInvisibleControl(): Boolean {
            if (this == ZWNJ || isWhitespace()) return false
            val type = Character.getType(this).toByte()
            return type == Character.CONTROL || type == Character.FORMAT
        }
    }
}
