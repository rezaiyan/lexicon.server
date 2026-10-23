package com.alirezaiyan.vokab.server.mcp

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/** An AI app the learner let in through the MCP sign-in, with the access they granted. */
data class McpConnectionDto(
    val id: String,
    val clientName: String,
    /** Where the app really lives (its metadata URL's host, else its redirect host), next to its self-chosen name. */
    val website: String?,
    val scopes: List<String>,
    val connectedAt: Long,
)

/**
 * The learner's MCP grants, for the app's "Connected apps" screen. Reads the authorization server's
 * tables directly, so it works whether or not the MCP server is switched on. Disconnecting deletes
 * the grants: `/mcp` checks every token against them, so access ends on the next call.
 */
@Service
class McpConnectionService(private val jdbc: NamedParameterJdbcTemplate) {

    @Transactional(readOnly = true)
    fun list(userId: Long): List<McpConnectionDto> =
        jdbc.query(LIVE_GRANTS_SQL, mapOf("principal" to userId.toString())) { rs, _ -> rs.toGrant() }
            .groupBy { it.clientId }
            .map { (clientId, grants) ->
                McpConnectionDto(
                    id = clientId,
                    clientName = grants.first().clientName,
                    website = grants.first().website,
                    scopes = grants.flatMap { it.scopes }.distinct().sorted(),
                    connectedAt = grants.minOf { it.issuedAt },
                )
            }
            .sortedByDescending { it.connectedAt }

    /** Revokes every grant the learner gave [clientId]. */
    @Transactional
    fun disconnect(userId: Long, clientId: String) {
        val params = mapOf("principal" to userId.toString(), "client" to clientId)
        val removed = jdbc.update(DELETE_GRANTS_SQL, params) + jdbc.update(DELETE_CONSENT_SQL, params)
        if (removed == 0) throw NoSuchElementException("Connected app not found")
    }

    private fun ResultSet.toGrant() = Grant(
        clientId = getString("client_id"),
        clientName = getString("client_name"),
        website = McpRegisteredClientRepository.publisherOf(getString("oauth_client_id"))
            ?: McpClientHosts.of(getString("redirect_uris").orEmpty().split(',').map { it.trim() }).firstOrNull(),
        scopes = getString("authorized_scopes").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        issuedAt = getTimestamp("issued_at").toInstant().toEpochMilli(),
    )

    private data class Grant(
        val clientId: String,
        val clientName: String,
        val website: String?,
        val scopes: List<String>,
        val issuedAt: Long,
    )

    private companion object {
        // A grant counts while any token on it can still be used
        const val LIVE_GRANTS_SQL = """
            SELECT c.id AS client_id, c.client_id AS oauth_client_id, c.client_name, c.redirect_uris, a.authorized_scopes,
                   COALESCE(a.access_token_issued_at, a.authorization_code_issued_at, now()) AS issued_at
            FROM oauth2_authorization a
            JOIN oauth2_registered_client c ON c.id = a.registered_client_id
            WHERE a.principal_name = :principal
              AND (a.access_token_expires_at > now() OR a.refresh_token_expires_at > now())
        """
        const val DELETE_GRANTS_SQL = "DELETE FROM oauth2_authorization " +
            "WHERE principal_name = :principal AND registered_client_id = :client"
        const val DELETE_CONSENT_SQL = "DELETE FROM oauth2_authorization_consent " +
            "WHERE principal_name = :principal AND registered_client_id = :client"
    }
}
