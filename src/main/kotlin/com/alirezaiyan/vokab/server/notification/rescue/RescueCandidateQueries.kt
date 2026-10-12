package com.alirezaiyan.vokab.server.notification.rescue

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp

/** Read-only SQL behind the comeback rescue: candidate words with their review history. */
@Service
class RescueCandidateQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** Every reviewed word at level 1–5 with its review counts and last five results. */
    @Transactional(readOnly = true)
    fun candidates(userId: Long): List<RescueCandidateRow> =
        jdbc.query(CANDIDATES_SQL, mapOf("userId" to userId)) { rs, _ ->
            RescueCandidateRow(
                wordId = rs.getLong("word_id"),
                text = rs.getString("original_word"),
                translation = rs.getString("translation"),
                sourceLanguage = rs.getString("source_language"),
                targetLanguage = rs.getString("target_language"),
                level = rs.getInt("level"),
                nextReviewDate = rs.getLong("next_review_date"),
                totalReviews = rs.getInt("total_reviews"),
                errors = rs.getInt("errors"),
                recent = rs.getString("recent") ?: "",
            )
        }

    @Transactional(readOnly = true)
    fun firstWord(userId: Long, sourceLanguage: String, targetLanguage: String): FirstWord? =
        jdbc.query(
            FIRST_WORD_SQL,
            mapOf("userId" to userId, "source" to sourceLanguage, "target" to targetLanguage),
        ) { rs, _ ->
            FirstWord(
                text = rs.getString("original_word"),
                translation = rs.getString("translation"),
                addedAt = rs.getObject("created_at", Timestamp::class.java).toInstant(),
            )
        }.firstOrNull()

    @Transactional(readOnly = true)
    fun totalWords(userId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM words WHERE user_id = :userId", mapOf("userId" to userId), Int::class.java) ?: 0

    private companion object {
        val CANDIDATES_SQL = """
            WITH ranked AS (
                SELECT e.word_id, e.rating, e.reviewed_at,
                       ROW_NUMBER() OVER (PARTITION BY e.word_id ORDER BY e.reviewed_at DESC) AS rn
                FROM review_events e
                WHERE e.user_id = :userId
            )
            SELECT w.id AS word_id, w.original_word, w.translation, w.source_language, w.target_language,
                   w.level, w.next_review_date,
                   COUNT(r.word_id) AS total_reviews,
                   SUM(CASE WHEN r.rating = 0 THEN 1 ELSE 0 END) AS errors,
                   STRING_AGG(
                       CASE WHEN r.rn <= 5 THEN CASE WHEN r.rating = 0 THEN '0' ELSE '1' END END,
                       '' ORDER BY r.reviewed_at DESC
                   ) AS recent
            FROM words w
            JOIN ranked r ON r.word_id = w.id
            WHERE w.user_id = :userId AND w.level BETWEEN 1 AND 5
            GROUP BY w.id, w.original_word, w.translation, w.source_language, w.target_language,
                     w.level, w.next_review_date
        """.trimIndent()

        val FIRST_WORD_SQL = """
            SELECT original_word, translation, created_at
            FROM words
            WHERE user_id = :userId AND source_language = :source AND target_language = :target
            ORDER BY created_at, id
            LIMIT 1
        """.trimIndent()
    }
}
