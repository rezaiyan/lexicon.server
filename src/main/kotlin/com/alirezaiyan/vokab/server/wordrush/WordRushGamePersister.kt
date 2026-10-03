package com.alirezaiyan.vokab.server.wordrush

import com.alirezaiyan.vokab.server.user.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

/**
 * Saves a single Word Rush game in its own independent transaction.
 *
 * Using REQUIRES_NEW here is critical: if one game in the client's sync batch
 * is malformed (e.g. a DB constraint violation), it must not roll back all other
 * games in the same batch. Each game either commits or is skipped independently.
 *
 * This must be a separate Spring bean (not an inner method of WordRushService) so
 * that Spring's proxy-based AOP can intercept the @Transactional annotation.
 */
@Component
class WordRushGamePersister(
    private val wordRushGameRepository: WordRushGameRepository,
    private val userRepository: UserRepository,
) {

    /**
     * Saves [gameReq] for [user] in its own REQUIRES_NEW transaction.
     * Returns true if the game was saved (or already existed), false if saving failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveGame(userId: Long, gameReq: SyncWordRushGameRequest): Boolean {
        val user = userRepository.getReferenceById(userId)
        return try {
            if (wordRushGameRepository.existsByUserAndClientGameId(user, gameReq.clientGameId)) {
                return true
            }

            wordRushGameRepository.save(
                WordRushGame(
                    user = user,
                    clientGameId = gameReq.clientGameId,
                    score = gameReq.score,
                    totalQuestions = gameReq.totalQuestions,
                    correctCount = gameReq.correctCount,
                    bestStreak = gameReq.bestStreak,
                    durationMs = gameReq.durationMs,
                    avgResponseMs = gameReq.avgResponseMs,
                    grade = gameReq.grade,
                    livesRemaining = gameReq.livesRemaining,
                    completedNormally = gameReq.completedNormally,
                    playedAt = gameReq.playedAt,
                )
            )
            true
        } catch (e: Exception) {
            logger.warn(e) { "Skipping game ${gameReq.clientGameId} for user ${user.id} due to error: ${e.message}" }
            false
        }
    }
}
