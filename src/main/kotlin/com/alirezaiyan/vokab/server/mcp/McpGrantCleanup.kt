package com.alirezaiyan.vokab.server.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Keeps the authorization server's tables small. Spring Authorization Server never deletes
 * anything itself, and registration is open, so:
 * - grants whose every token expired more than a day ago go (nothing can use them any more);
 * - clients nobody connected with go a week after registering (or after their metadata was last
 *   fetched); a metadata-document client simply gets fetched again if it comes back.
 */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpGrantCleanup(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {

    @Scheduled(cron = "0 40 0 * * *") // 00:40 UTC nightly
    fun nightly() {
        runCatching { purge() }
            .onSuccess { (grants, clients) -> logger.info { "MCP cleanup: grants=$grants clients=$clients" } }
            .onFailure { logger.error(it) { "MCP cleanup failed" } }
    }

    /** Returns (grants removed, clients removed). */
    @Transactional
    fun purge(): Pair<Int, Int> {
        val now = Instant.now(clock)
        val grants = jdbc.update(DEAD_GRANTS_SQL, mapOf("before" to Timestamp.from(now.minus(GRANT_GRACE))))
        val clients = jdbc.update(UNUSED_CLIENTS_SQL, mapOf("before" to Timestamp.from(now.minus(UNUSED_CLIENT_AGE))))
        return grants to clients
    }

    private companion object {
        val GRANT_GRACE: Duration = Duration.ofDays(1)
        val UNUSED_CLIENT_AGE: Duration = Duration.ofDays(7)

        const val DEAD_GRANTS_SQL = """
            DELETE FROM oauth2_authorization
            WHERE GREATEST(
                COALESCE(authorization_code_expires_at, 'epoch'),
                COALESCE(access_token_expires_at, 'epoch'),
                COALESCE(refresh_token_expires_at, 'epoch')
            ) < :before
        """
        const val UNUSED_CLIENTS_SQL = """
            DELETE FROM oauth2_registered_client c
            WHERE c.client_id_issued_at < :before
              AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_consent g WHERE g.registered_client_id = c.id)
        """
    }
}
