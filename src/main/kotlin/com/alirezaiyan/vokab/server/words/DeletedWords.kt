package com.alirezaiyan.vokab.server.words

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Tombstones of deleted words. The word sync only returns words changed since a timestamp, so
 * devices learn about deletions here instead; without them a word deleted on one device (or by a
 * connected AI app) would stay on the others.
 */
@Component
class DeletedWords(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    fun record(userId: Long, wordIds: Collection<Long>) {
        if (wordIds.isEmpty()) return
        val now = Timestamp.from(Instant.now(clock))
        jdbc.batchUpdate(
            """
            INSERT INTO deleted_words (user_id, word_id, deleted_at) VALUES (:userId, :wordId, :deletedAt)
            ON CONFLICT (user_id, word_id) DO UPDATE SET deleted_at = EXCLUDED.deleted_at
            """.trimIndent(),
            wordIds.map { mapOf("userId" to userId, "wordId" to it, "deletedAt" to now) }.toTypedArray(),
        )
    }

    /** Ids of the user's words deleted after [since], oldest deletion first. */
    fun since(userId: Long, since: Instant): List<Long> = jdbc.queryForList(
        "SELECT word_id FROM deleted_words WHERE user_id = :userId AND deleted_at > :since ORDER BY deleted_at",
        mapOf("userId" to userId, "since" to Timestamp.from(since)),
        Long::class.java,
    )

    /**
     * Devices that haven't synced for [RETENTION] start over with a full sync anyway, so older
     * tombstones are no longer needed.
     */
    @Scheduled(cron = "0 50 0 * * *") // 00:50 UTC nightly
    fun purgeOld() {
        val removed = jdbc.update(
            "DELETE FROM deleted_words WHERE deleted_at < :cutoff",
            mapOf("cutoff" to Timestamp.from(Instant.now(clock).minus(RETENTION))),
        )
        if (removed > 0) logger.info { "Purged $removed word tombstones older than ${RETENTION.toDays()} days" }
    }

    companion object {
        val RETENTION: Duration = Duration.ofDays(180)
    }
}
