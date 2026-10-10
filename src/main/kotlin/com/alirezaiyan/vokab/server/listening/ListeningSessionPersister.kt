package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Saves a single listening session and its heard words in an independent transaction, so one
 * malformed session in a sync batch never rolls back the others. A separate bean so Spring's
 * proxy applies REQUIRES_NEW (see WordRushGamePersister).
 *
 * Failures propagate: a constraint violation marks the transaction rollback-only, so swallowing it
 * here would only resurface as UnexpectedRollbackException on commit. [ListeningService] skips them.
 */
@Component
class ListeningSessionPersister(
    private val sessionRepository: ListeningSessionRepository,
    private val wordRepository: ListeningSessionWordRepository,
    private val userRepository: UserRepository,
) {

    /** Saves [req] unless a session with its client id already exists (sync is idempotent). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveSession(userId: Long, req: SyncListeningSessionRequest) {
        val user = userRepository.getReferenceById(userId)
        if (sessionRepository.existsByUserAndClientSessionId(user, req.clientSessionId)) return

        val session = sessionRepository.save(
            ListeningSession(
                user = user,
                clientSessionId = req.clientSessionId,
                source = req.source,
                sourceDetail = req.sourceDetail,
                wordOrder = req.order,
                repeatCount = req.repeatCount,
                pauseMs = req.pauseMs,
                speechRate = req.speechRate,
                plannedWords = req.plannedWords,
                wordsHeard = req.wordsHeard,
                wordsSkipped = req.wordsSkipped,
                pauseCount = req.pauseCount,
                listeningMs = req.listeningMs,
                durationMs = req.durationMs,
                completedNormally = req.completedNormally,
                startedAt = req.startedAt,
                endedAt = req.endedAt,
            )
        )
        wordRepository.saveAll(
            req.words.map { word ->
                ListeningSessionWord(
                    session = session,
                    user = user,
                    wordId = word.wordId,
                    sourceLanguage = word.sourceLanguage,
                    targetLanguage = word.targetLanguage,
                    heardAt = word.heardAt,
                )
            }
        )
    }
}
