package com.alirezaiyan.vokab.server.service

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * Erases every row a user owns, ahead of deleting the `users` row itself.
 *
 * Deliberately explicit: production foreign keys are mostly `NO ACTION` (they drifted from the
 * `ON DELETE CASCADE` in the migrations), and `notification_log` has no FK at all, so nothing here
 * relies on cascades. Statements run children-before-parents in one set-based query per table,
 * inside the caller's transaction.
 *
 * Kept on purpose: `app_events` (anonymous product analytics) and `audit_log` (the deletion record
 * itself). `email_log` rows are detached (`user_id = NULL`), matching its `ON DELETE SET NULL`.
 *
 * Adding a table with a `user_id` column? Add it here, or account deletion fails on its FK.
 */
@Component
class UserDataPurger(private val jdbc: NamedParameterJdbcTemplate) {

    /** Returns the number of affected rows per table, in execution order. */
    fun purge(userId: Long): Map<String, Int> {
        val params = mapOf("userId" to userId)
        return STATEMENTS.associate { (table, sql) -> table to jdbc.update(sql, params) }
    }

    companion object {
        internal val STATEMENTS: List<Pair<String, String>> = listOf(
            "word_tags" to """
                DELETE FROM word_tags
                WHERE word_id IN (SELECT id FROM words WHERE user_id = :userId)
                   OR tag_id IN (SELECT id FROM tags WHERE user_id = :userId)
            """.trimIndent(),
            "review_events" to "DELETE FROM review_events WHERE user_id = :userId",
            "study_sessions" to "DELETE FROM study_sessions WHERE user_id = :userId",
            "words" to "DELETE FROM words WHERE user_id = :userId",
            "tags" to "DELETE FROM tags WHERE user_id = :userId",
            "word_rush_games" to "DELETE FROM word_rush_games WHERE user_id = :userId",
            "refresh_tokens" to "DELETE FROM refresh_tokens WHERE user_id = :userId",
            "push_tokens" to "DELETE FROM push_tokens WHERE user_id = :userId",
            "daily_insights" to "DELETE FROM daily_insights WHERE user_id = :userId",
            "daily_activities" to "DELETE FROM daily_activities WHERE user_id = :userId",
            "subscriptions" to "DELETE FROM subscriptions WHERE user_id = :userId",
            "user_settings" to "DELETE FROM user_settings WHERE user_id = :userId",
            "user_platforms" to "DELETE FROM user_platforms WHERE user_id = :userId",
            "notification_schedule" to "DELETE FROM notification_schedule WHERE user_id = :userId",
            "notification_log" to "DELETE FROM notification_log WHERE user_id = :userId",
            "email_subscriptions" to "DELETE FROM email_subscriptions WHERE user_id = :userId",
            "email_log" to "UPDATE email_log SET user_id = NULL WHERE user_id = :userId",
        )
    }
}
