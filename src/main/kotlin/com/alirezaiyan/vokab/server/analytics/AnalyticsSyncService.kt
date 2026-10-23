package com.alirezaiyan.vokab.server.analytics

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/** Stores study sessions uploaded by the app. */
@Service
class AnalyticsSyncService(private val sessionPersister: AnalyticsSessionPersister) {
    // Not @Transactional at the batch level — each session is saved in its own
    // REQUIRES_NEW transaction via sessionPersister so one bad session in the
    // client's retry queue cannot roll back the entire batch forever.
    fun syncSessions(userId: Long, request: SyncAnalyticsRequest): SyncAnalyticsResponse {
        val syncedIds = mutableListOf<String>()

        for (sessionReq in request.sessions) {
            if (sessionPersister.saveSession(userId, sessionReq)) {
                syncedIds.add(sessionReq.clientSessionId)
            }
        }

        logger.info { "Synced ${syncedIds.size}/${request.sessions.size} sessions for user $userId" }
        return SyncAnalyticsResponse(syncedSessionIds = syncedIds)
    }
}
