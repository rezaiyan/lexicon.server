package com.alirezaiyan.vokab.server.listening

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class ListeningService(
    private val persister: ListeningSessionPersister,
) {

    /** Saves each session independently; a failing one is skipped and left out of the response. */
    fun syncSessions(userId: Long, request: SyncListeningRequest): SyncListeningResponse {
        val syncedIds = request.sessions.mapNotNull { session ->
            runCatching { persister.saveSession(userId, session) }
                .onFailure { e ->
                    logger.warn(e) { "Skipping listening session ${session.clientSessionId} for user $userId" }
                }
                .map { session.clientSessionId }
                .getOrNull()
        }

        logger.info { "Synced ${syncedIds.size}/${request.sessions.size} listening sessions for user $userId" }
        return SyncListeningResponse(syncedSessionIds = syncedIds)
    }
}
