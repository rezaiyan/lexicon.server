package com.alirezaiyan.vokab.server.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/** Runs the real purge SQL against the migrated PostgreSQL test schema. */
@DataJpaTest
@ActiveProfiles("test")
@Import(UserDataPurger::class)
class UserDataPurgerTest {

    @Autowired private lateinit var purger: UserDataPurger
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `purge removes every row the user owns and leaves other users untouched`() {
        val victim = seedUserWithData("victim@example.com")
        val bystander = seedUserWithData("bystander@example.com")

        purger.purge(victim)

        // Asserted before the users row is deleted, so this holds even where FKs don't cascade.
        ownedTables().forEach { table ->
            assertEquals(0, count(table, victim), "$table still has rows for the purged user")
            assertEquals(1, count(table, bystander), "$table lost rows of another user")
        }
        assertEquals(0, wordTagCount(victim))
        assertEquals(1, wordTagCount(bystander))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM email_log WHERE user_id IS NULL", Int::class.java))
        assertEquals(1, count("email_log", bystander))

        // With all children gone, the user row deletes cleanly.
        assertEquals(1, jdbc.update("DELETE FROM users WHERE id = ?", victim))
    }

    @Test
    fun `every table with a user_id column is handled by the purge`() {
        val tablesWithUserId = jdbc.queryForList(
            // Base tables only: the analytics views also expose user_id but hold no data of their own
            """
            SELECT c.table_name FROM information_schema.columns c
            JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.column_name = 'user_id' AND c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
            """,
            String::class.java,
        ).toSet()
        val handled = UserDataPurger.STATEMENTS.map { it.first }.toSet()
        // Guard against a vacuous pass if the metadata query stops matching.
        assertTrue("words" in tablesWithUserId && "notification_log" in tablesWithUserId, "schema scan found: $tablesWithUserId")

        // Kept on purpose (see UserDataPurger): no FK, rows outlive the account
        val retained = setOf("app_events", "audit_log")
        val missing = tablesWithUserId - handled - retained
        assertTrue(missing.isEmpty(), "Tables with user_id not covered by UserDataPurger: $missing")
    }

    /** The tables [seedUserWithData] fills — fixed here, not derived from the code under test. */
    private fun ownedTables(): List<String> = listOf(
        "review_events", "study_sessions", "words", "tags", "word_rush_games", "listening_sessions",
        "listening_session_words", "refresh_tokens",
        "push_tokens", "daily_insights", "daily_activities", "subscriptions", "user_settings",
        "user_platforms", "notification_schedule", "notification_log", "email_subscriptions",
        "credit_wallets", "credit_transactions",
    )

    private fun count(table: String, userId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id = ?", Int::class.java, userId) ?: -1

    private fun wordTagCount(userId: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM word_tags wt JOIN words w ON w.id = wt.word_id WHERE w.user_id = ?",
        Int::class.java, userId,
    ) ?: -1

    /** One row in every user-owned table. */
    private fun seedUserWithData(email: String): Long {
        jdbc.update("INSERT INTO users (email, name) VALUES (?, 'Test')", email)
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long::class.java, email)!!

        jdbc.update("INSERT INTO words (user_id, original_word, translation, source_language, target_language) VALUES (?, 'Hund', 'dog', 'de', 'en')", id)
        val wordId = jdbc.queryForObject("SELECT id FROM words WHERE user_id = ?", Long::class.java, id)!!
        jdbc.update("INSERT INTO tags (user_id, name) VALUES (?, 'animals')", id)
        val tagId = jdbc.queryForObject("SELECT id FROM tags WHERE user_id = ?", Long::class.java, id)!!
        jdbc.update("INSERT INTO word_tags (word_id, tag_id) VALUES (?, ?)", wordId, tagId)

        jdbc.update("INSERT INTO study_sessions (user_id, client_session_id, started_at) VALUES (?, 's1', 0)", id)
        val sessionId = jdbc.queryForObject("SELECT id FROM study_sessions WHERE user_id = ?", Long::class.java, id)!!
        jdbc.update(
            "INSERT INTO review_events (session_id, user_id, word_id, rating, previous_level, new_level, reviewed_at) VALUES (?, ?, ?, 1, 0, 1, 0)",
            sessionId, id, wordId,
        )

        jdbc.update("INSERT INTO word_rush_games (user_id, client_game_id, played_at) VALUES (?, 'g1', 0)", id)
        jdbc.update(
            "INSERT INTO listening_sessions (user_id, client_session_id, source, word_order, started_at, ended_at) VALUES (?, 'l1', 'due', 'word_first', 0, 0)",
            id,
        )
        val listeningId = jdbc.queryForObject("SELECT id FROM listening_sessions WHERE user_id = ?", Long::class.java, id)!!
        jdbc.update(
            "INSERT INTO listening_session_words (session_id, user_id, word_id, source_language, target_language, heard_at) VALUES (?, ?, ?, 'en', 'de', 0)",
            listeningId, id, wordId,
        )
        jdbc.update("INSERT INTO refresh_tokens (token_hash, user_id, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP)", "hash-$email", id)
        jdbc.update("INSERT INTO push_tokens (user_id, token, platform) VALUES (?, ?, 'IOS')", id, "push-$email")
        jdbc.update("INSERT INTO daily_insights (user_id, insight_text, generated_at, date) VALUES (?, 'hi', CURRENT_TIMESTAMP, '2026-10-02')", id)
        jdbc.update("INSERT INTO daily_activities (user_id, activity_date) VALUES (?, DATE '2026-10-02')", id)
        jdbc.update("INSERT INTO subscriptions (user_id, product_id, status, started_at) VALUES (?, 'p', 'ACTIVE', CURRENT_TIMESTAMP)", id)
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id)
        jdbc.update("INSERT INTO user_platforms (user_id, platform) VALUES (?, 'IOS')", id)
        jdbc.update("INSERT INTO notification_schedule (user_id) VALUES (?)", id)
        jdbc.update("INSERT INTO notification_log (user_id, notification_type) VALUES (?, 'STREAK')", id)
        jdbc.update("INSERT INTO email_subscriptions (user_id, category) VALUES (?, 'newsletter')", id)
        jdbc.update(
            "INSERT INTO credit_wallets (user_id, tier, allowance_remaining, period_start, period_end, bonus_balance, created_at, updated_at) " +
                "VALUES (?, 'FREE', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 15, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            id,
        )
        jdbc.update(
            "INSERT INTO credit_transactions (user_id, type, allowance_delta, bonus_delta, created_at) VALUES (?, 'SIGNUP_BONUS', 0, 15, CURRENT_TIMESTAMP)",
            id,
        )
        jdbc.update(
            "INSERT INTO email_log (user_id, recipient_email, category, template_id, subject) VALUES (?, ?, 'c', 't', 's')",
            id, email,
        )
        return id
    }
}
