package com.alirezaiyan.vokab.server.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class AuditLogService(
    private val auditLogRepository: AuditLogRepository
) {
    private val metadataJson = ObjectMapper()

    @Async
    fun logLogin(userId: Long, email: String, provider: String, ipAddress: String?, userAgent: String?) {
        logger.info { "AUDIT: LOGIN userId=$userId provider=$provider ip=$ipAddress" }
        save(
            eventType = AuditEventType.LOGIN,
            userId = userId,
            email = email,
            ipAddress = ipAddress,
            userAgent = userAgent,
            metadata = """{"provider":"$provider"}"""
        )
    }

    @Async
    fun logRefresh(userId: Long, email: String, familyId: String, ipAddress: String?, userAgent: String?) {
        logger.info { "AUDIT: TOKEN_REFRESH userId=$userId familyId=$familyId ip=$ipAddress" }
        save(
            eventType = AuditEventType.TOKEN_REFRESH,
            userId = userId,
            email = email,
            ipAddress = ipAddress,
            userAgent = userAgent,
            metadata = """{"familyId":"$familyId"}"""
        )
    }

    /**
     * @param tokenLookupHash the refresh token's lookup hash (never the plaintext token); only a short
     *   prefix is stored, enough to correlate with the refresh_tokens row.
     */
    @Async
    fun logLogout(userId: Long, email: String, tokenLookupHash: String, ipAddress: String?) {
        logger.info { "AUDIT: LOGOUT userId=$userId ip=$ipAddress" }
        save(
            eventType = AuditEventType.LOGOUT,
            userId = userId,
            email = email,
            ipAddress = ipAddress,
            metadata = metadataJson.writeValueAsString(mapOf("tokenHashPrefix" to tokenLookupHash.take(8)))
        )
    }

    @Async
    fun logLogoutAll(userId: Long, email: String, ipAddress: String?) {
        logger.info { "AUDIT: LOGOUT_ALL userId=$userId ip=$ipAddress" }
        save(
            eventType = AuditEventType.LOGOUT_ALL,
            userId = userId,
            email = email,
            ipAddress = ipAddress
        )
    }

    @Async
    fun logTokenRevocation(userId: Long, familyId: String, reason: String, ipAddress: String?) {
        logger.warn { "AUDIT: TOKEN_REVOCATION userId=$userId familyId=$familyId reason=$reason ip=$ipAddress" }
        save(
            eventType = AuditEventType.TOKEN_REVOCATION,
            userId = userId,
            ipAddress = ipAddress,
            metadata = """{"familyId":"$familyId","reason":"$reason"}"""
        )
    }

    @Async
    fun logTokenReuse(userId: Long, familyId: String, ipAddress: String?) {
        logger.error { "AUDIT: TOKEN_REUSE (security violation) userId=$userId familyId=$familyId ip=$ipAddress" }
        save(
            eventType = AuditEventType.TOKEN_REUSE,
            userId = userId,
            ipAddress = ipAddress,
            metadata = """{"familyId":"$familyId"}"""
        )
    }

    @Async
    fun logAccountReactivation(userId: Long, email: String, provider: String, ipAddress: String?) {
        logger.info { "AUDIT: ACCOUNT_REACTIVATION userId=$userId provider=$provider ip=$ipAddress" }
        save(
            eventType = AuditEventType.ACCOUNT_REACTIVATION,
            userId = userId,
            email = email,
            ipAddress = ipAddress,
            metadata = """{"provider":"$provider"}"""
        )
    }

    // Not @Async — account deletion audit must be committed before the user row is deleted
    fun logAccountDeletion(userId: Long, email: String, ipAddress: String?) {
        logger.warn { "AUDIT: ACCOUNT_DELETION userId=$userId ip=$ipAddress" }
        save(
            eventType = AuditEventType.ACCOUNT_DELETION,
            userId = userId,
            email = email,
            ipAddress = ipAddress
        )
    }

    /**
     * Not @Async: cheap, and the record must exist by the time the tool answers. Holds word ids,
     * never word text, so it reveals nothing after the account (and its words) are deleted.
     */
    fun logMcpWrite(userId: Long, clientId: String, tool: String, wordIds: List<Long>) {
        logger.info { "AUDIT: MCP_WRITE userId=$userId tool=$tool words=${wordIds.size}" }
        save(
            eventType = AuditEventType.MCP_WRITE,
            userId = userId,
            metadata = metadataJson.writeValueAsString(
                mapOf("client" to clientId, "tool" to tool, "wordIds" to wordIds)
            ),
        )
    }

    private fun save(
        eventType: AuditEventType,
        userId: Long? = null,
        email: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
        metadata: String? = null
    ) {
        try {
            auditLogRepository.save(
                AuditLog(
                    eventType = eventType,
                    userId = userId,
                    email = email,
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                    metadata = metadata
                )
            )
        } catch (e: Exception) {
            // Audit failure must never break the main flow
            logger.error(e) { "Failed to persist audit log for $eventType userId=$userId" }
        }
    }
}
