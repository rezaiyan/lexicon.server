package com.alirezaiyan.vokab.server.mcp

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

@SpringBootTest(properties = [MCP_ENABLED, MCP_TEST_ISSUER_PROPERTY, MCP_TEST_KEY_PROPERTY])
@ActiveProfiles("test")
@Transactional
class McpGrantCleanupTest {

    @Autowired private lateinit var cleanup: McpGrantCleanup
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `removes dead grants and clients nobody connected with, keeps everything in use`() {
        client("old-unused", ageDays = 30)
        client("old-in-use", ageDays = 30)
        client("old-consented", ageDays = 30)
        client("new-unused", ageDays = 1)
        grant("live", client = "old-in-use", refreshExpiresInDays = 10)
        grant("dead", client = "old-in-use", refreshExpiresInDays = -3)
        jdbc.update(
            "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities) VALUES ('old-consented', '1', 'SCOPE_words:read')"
        )

        cleanup.purge()

        assertEquals(setOf("old-in-use", "old-consented", "new-unused"), ids("SELECT id FROM oauth2_registered_client"))
        assertEquals(setOf("live"), ids("SELECT id FROM oauth2_authorization"))
    }

    private fun ids(sql: String) = jdbc.queryForList(sql, String::class.java).toSet()

    private fun client(id: String, ageDays: Int) = jdbc.update(
        """
        INSERT INTO oauth2_registered_client (id, client_id, client_id_issued_at, client_name, client_authentication_methods,
            authorization_grant_types, scopes, client_settings, token_settings)
        VALUES (?, ?, now() - make_interval(days => ?), 'c', 'none', 'authorization_code', 'words:read', '{}', '{}')
        """.trimIndent(),
        id, "client-$id", ageDays,
    )

    private fun grant(id: String, client: String, refreshExpiresInDays: Int) = jdbc.update(
        """
        INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, authorization_grant_type,
            access_token_value, access_token_expires_at, refresh_token_value, refresh_token_expires_at)
        VALUES (?, ?, '1', 'authorization_code', ?, now() - interval '5 days', ?, now() + make_interval(days => ?))
        """.trimIndent(),
        id, client, "at-$id", "rt-$id", refreshExpiresInDays,
    )
}
