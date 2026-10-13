package com.alirezaiyan.vokab.server.notification.rescue

/** A true, checkable statement about the learner; [words] are target-language words it names. */
data class RescueFact(val id: String, val text: String, val words: Set<String> = emptySet())

/**
 * Everything the AI may say about the learner. Copy must cite the facts it uses, and the linter
 * rejects any number or quoted word not found in them — the AI cannot invent stats.
 */
data class RescueFactSheet(val facts: List<RescueFact>) {
    val allWords: Set<String> get() = facts.flatMap { it.words }.toSet()

    fun byIds(ids: Collection<String>): List<RescueFact> = facts.filter { it.id in ids }

    fun render(): String = facts.joinToString("\n") { "${it.id} ${it.text}" }

    /** The same sheet for a session of [size] words, so copy is checked against the words actually picked. */
    fun withSessionSize(size: Int): RescueFactSheet = copy(
        facts = facts.map {
            if (it.id != RescueFactSheetBuilder.SESSION_FACT_ID) it
            else it.copy(text = RescueFactSheetBuilder.sessionFact(size))
        }
    )
}

data class RescueProfile(
    val daysAway: Long,
    val totalWords: Int,
    val longestStreak: Int,
    val firstWord: FirstWord?,
)

object RescueFactSheetBuilder {
    const val SESSION_SIZE = 8
    const val EST_MINUTES = 3

    /** The session-size fact always comes first. */
    const val SESSION_FACT_ID = "F1"

    private const val MIN_STREAK_WORTH_MENTIONING = 3
    private const val MAX_WORD_FACTS_PER_BUCKET = 3

    /** Words to ask for: [SESSION_SIZE], or every candidate when the pool is smaller. */
    fun sessionSize(pool: RescuePool): Int = minOf(SESSION_SIZE, pool.all.size)

    fun sessionFact(size: Int): String = "session: $size words, about $EST_MINUTES minutes"

    fun build(profile: RescueProfile, pool: RescuePool): RescueFactSheet {
        val facts = mutableListOf<RescueFact>()
        fun add(text: String, words: Set<String> = emptySet()) {
            facts += RescueFact("F${facts.size + 1}", text, words)
        }

        add(sessionFact(sessionSize(pool)))
        add("days since last study: ${profile.daysAway}")
        add("words in deck: ${profile.totalWords} (${pool.targetLanguage})")
        if (profile.longestStreak >= MIN_STREAK_WORTH_MENTIONING) {
            add("longest streak ever: ${profile.longestStreak} days")
        }

        val levelFive = pool.nearMastery.filter { it.level == 5 }
        if (levelFive.size >= 2) add("words one review from mastered: ${levelFive.size}")
        levelFive.take(MAX_WORD_FACTS_PER_BUCKET).forEach {
            add("near-mastery word \"${it.text}\" (${it.translation}): one review from mastered", setOf(it.text))
        }

        pool.weak.take(MAX_WORD_FACTS_PER_BUCKET).forEach {
            val misses = it.recent.count { r -> r == '0' }
            val lastTry = if (it.lastCorrect) ", right on the last try" else ""
            add(
                "weak word \"${it.text}\" (${it.translation}): missed $misses of last ${it.recent.length}$lastTry",
                setOf(it.text),
            )
        }

        profile.firstWord
            ?.takeIf { first -> pool.all.any { it.text == first.text } }
            ?.let { add("first word ever added: \"${it.text}\" (${it.translation})", setOf(it.text)) }

        return RescueFactSheet(facts)
    }
}
