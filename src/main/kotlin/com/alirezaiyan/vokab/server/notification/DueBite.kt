package com.alirezaiyan.vokab.server.notification

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A due word, enough to pick a bite from. */
data class DueWordRow(val wordId: Long, val level: Int, /** Epoch millis. */ val nextReviewDate: Long)

/** A short review a due push offers: [wordIds] in session order. */
data class DueBite(val wordIds: List<Long>, val minutes: Int, val mostlyKnown: Boolean) {

    /**
     * Like a comeback rescue, a due push asks for a few minutes, not the whole backlog: quoting
     * "154 min" makes nobody tap. Easy wins open and close the bite, with at most [MAX_HARD]
     * shaky words between them; the most overdue go first.
     */
    companion object {
        const val SIZE = 8
        private const val MAX_HARD = 2
        private const val HARD_MAX_LEVEL = 2
        private const val SECONDS_PER_WORD = 20

        fun pick(due: List<DueWordRow>): DueBite? {
            if (due.isEmpty()) return null
            val (hard, easy) = due.sortedWith(compareBy({ it.nextReviewDate }, { it.wordId }))
                .partition { it.level <= HARD_MAX_LEVEL }
            val easyPicks = easy.take(SIZE - minOf(hard.size, MAX_HARD))
            val hardPicks = hard.take(SIZE - easyPicks.size)
            val ordered = easyPicks.take(2) + hardPicks + easyPicks.drop(2)
            return DueBite(
                wordIds = ordered.map { it.wordId },
                minutes = maxOf(1, (ordered.size * SECONDS_PER_WORD + 59) / 60),
                mostlyKnown = easyPicks.size * 2 > ordered.size,
            )
        }
    }
}

/** Read-only SQL behind due pushes: the user's due words, most overdue first. */
@Service
class DueWordQueries(private val jdbc: NamedParameterJdbcTemplate) {

    @Transactional(readOnly = true)
    fun dueWords(userId: Long, nowMs: Long): List<DueWordRow> =
        jdbc.query(DUE_WORDS_SQL, mapOf("userId" to userId, "nowMs" to nowMs)) { rs, _ ->
            DueWordRow(
                wordId = rs.getLong("id"),
                level = rs.getInt("level"),
                nextReviewDate = rs.getLong("next_review_date"),
            )
        }

    private companion object {
        /** Enough candidates for any bite without loading a huge backlog. */
        const val DUE_WORDS_SQL = """
            SELECT id, level, next_review_date
            FROM words
            WHERE user_id = :userId AND next_review_date <= :nowMs
            ORDER BY next_review_date, id
            LIMIT 200
        """
    }
}
