package com.alirezaiyan.vokab.server.ai.instructions

/**
 * Free, deterministic first line of the guard: refuses instructions that are obviously an
 * injection attempt or carry personal data, before any model is asked. It is deliberately
 * narrow (false rejects hurt real learners); the judge handles everything subtler.
 */
object InstructionPrescreen {

    private val MARKUP = Regex("""</?\s*[a-zA-Z_][\w-]*\s*/?>|<<<|>>>|```|\{\{|}}""")

    private val ROLE_PREFIX = Regex("""(?i)\b(system|assistant|developer)\s*:""")

    private val INJECTION_PHRASES = listOf(
        // English
        Regex(
            """(?i)\b(ignore|disregard|forget|override)\s+(all|any|the|your|my|previous|prior|above)\b""" +
                """.{0,20}\b(instructions?|rules?|prompts?)\b""",
        ),
        Regex("""(?i)\b(system|hidden|original)\s+prompt\b"""),
        Regex("""(?i)\byou\s+are\s+now\b|\bjailbreak\b|\bdeveloper\s+mode\b"""),
        // German
        Regex(
            """(?i)\b(ignorier|vergiss|missachte)\w*\s+(alle|die|deine|vorherigen)\b.{0,20}(anweisung|regel|prompt)""",
        ),
        Regex("""(?i)\bsystem-?prompt\b"""),
        // Persian
        Regex("""نادیده\s*بگیر|دستورات\s*(قبلی|بالا)|پرامپت\s*سیستم"""),
    )

    private val URL = Regex("""(?i)\bhttps?://|\bwww\.|\b[\w-]+\.(com|net|org|io|ir|de|co|me|app|xyz)\b""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val PHONE = Regex("""\+?\d[\d\s().-]{7,}\d""")
    private val REPEATED_CHAR = Regex("""(.)\1{9,}""")

    /** The rejection reason, or null when the instruction may go on to the judge. */
    fun check(instruction: UserInstruction): InstructionRejection? {
        val text = instruction.text
        return when {
            MARKUP.containsMatchIn(text) || ROLE_PREFIX.containsMatchIn(text) -> InstructionRejection.INJECTION
            INJECTION_PHRASES.any { it.containsMatchIn(text) } -> InstructionRejection.INJECTION
            URL.containsMatchIn(text) || EMAIL.containsMatchIn(text) || containsPhoneNumber(text) ->
                InstructionRejection.PERSONAL_DATA
            REPEATED_CHAR.containsMatchIn(text) -> InstructionRejection.OFF_TOPIC
            else -> null
        }
    }

    // Digit runs like "1000 2000" are not phone numbers; nine digits or more are
    private fun containsPhoneNumber(text: String): Boolean =
        PHONE.findAll(text).any { match -> match.value.count(Char::isDigit) >= MIN_PHONE_DIGITS }

    private const val MIN_PHONE_DIGITS = 9
}
