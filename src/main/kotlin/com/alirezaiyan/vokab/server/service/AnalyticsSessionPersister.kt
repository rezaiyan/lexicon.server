package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.ReviewEvent
import com.alirezaiyan.vokab.server.domain.entity.StudySession
import com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository
import com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.presentation.dto.SyncSessionRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Saves a single analytics session and its events in its own independent transaction.
 *
 * Using REQUIRES_NEW here is critical: if one session in the client's retry queue is
 * malformed (e.g. a DB constraint violation), it must not roll back all other sessions
 * in the same batch. Each session either commits or is skipped independently.
 *
 * This must be a separate Spring bean (not an inner method of AnalyticsService) so
 * that Spring's proxy-based AOP can intercept the @Transactional annotation.
 */
@Component
class AnalyticsSessionPersister(
    private val studySessionRepository: StudySessionRepository,
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    /**
     * Saves [sessionReq] for [user] in its own REQUIRES_NEW transaction.
     * Returns true if the session was saved (or already existed), false if saving failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveSession(userId: Long, sessionReq: SyncSessionRequest): Boolean {
        val user = userRepository.getReferenceById(userId)
        return try {
            val existing = studySessionRepository.findByUserAndClientSessionId(user, sessionReq.clientSessionId)
            if (existing.isPresent) return true

            val session = studySessionRepository.save(
                StudySession(
                    user = user,
                    clientSessionId = sessionReq.clientSessionId,
                    startedAt = sessionReq.startedAt,
                    endedAt = sessionReq.endedAt,
                    durationMs = sessionReq.durationMs,
                    totalCards = sessionReq.totalCards,
                    correctCount = sessionReq.correctCount,
                    incorrectCount = sessionReq.incorrectCount,
                    reviewType = sessionReq.reviewType,
                    completedNormally = sessionReq.completedNormally,
                    sourceLanguage = sessionReq.sourceLanguage,
                    targetLanguage = sessionReq.targetLanguage,
                    triggerSource = sessionReq.triggerSource,
                )
            )

            val events = sessionReq.events.map { eventReq ->
                ReviewEvent(
                    session = session,
                    user = user,
                    wordId = eventReq.wordId,
                    wordText = eventReq.wordText,
                    wordTranslation = eventReq.wordTranslation,
                    sourceLanguage = eventReq.sourceLanguage,
                    targetLanguage = eventReq.targetLanguage,
                    rating = eventReq.rating,
                    previousLevel = eventReq.previousLevel,
                    newLevel = eventReq.newLevel,
                    responseTimeMs = eventReq.responseTimeMs,
                    reviewedAt = eventReq.reviewedAt,
                )
            }
            reviewEventRepository.saveAll(events)

            if (user.firstReviewAt == null) {
                user.firstReviewAt = Instant.now(clock)
                userRepository.save(user)
            }

            true
        } catch (e: Exception) {
            logger.warn(e) { "Skipping session ${sessionReq.clientSessionId} for user ${user.id} due to error: ${e.message}" }
            false
        }
    }
}
