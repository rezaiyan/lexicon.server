package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.WordRushGameRepository
import com.alirezaiyan.vokab.server.presentation.dto.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.alirezaiyan.vokab.server.domain.entity.requireId

private val logger = KotlinLogging.logger {}

@Service
class WordRushService(
    private val wordRushGameRepository: WordRushGameRepository,
    private val wordRushGamePersister: WordRushGamePersister,
) {

    fun syncGames(user: User, request: SyncWordRushRequest): SyncWordRushResponse {
        val syncedIds = mutableListOf<String>()

        for (gameReq in request.games) {
            if (wordRushGamePersister.saveGame(user, gameReq)) {
                syncedIds.add(gameReq.clientGameId)
            }
        }

        logger.info { "Synced ${syncedIds.size}/${request.games.size} Word Rush games for user ${user.id}" }
        return SyncWordRushResponse(syncedGameIds = syncedIds)
    }

    @Transactional(readOnly = true)
    fun getInsights(user: User): WordRushInsightsResponse {
        val p = wordRushGameRepository.findInsightsByUserId(user.requireId())
        val completionRatePercent = if (p.totalGames > 0) p.totalCompleted.toDouble() / p.totalGames * 100 else 0.0
        val avgDurationMs = if (p.totalGames > 0) p.totalTimePlayedMs.toDouble() / p.totalGames else 0.0

        return WordRushInsightsResponse(
            totalGames = p.totalGames,
            totalCompleted = p.totalCompleted,
            completionRatePercent = completionRatePercent,
            bestStreakEver = p.bestStreakEver,
            avgScore = p.avgScore,
            avgAccuracyPercent = p.avgAccuracyPercent,
            totalTimePlayedMs = p.totalTimePlayedMs,
            avgDurationMs = avgDurationMs,
            avgResponseMs = p.avgResponseMs,
        )
    }

    @Transactional(readOnly = true)
    fun getHistory(user: User): List<WordRushGameResponse> {
        return wordRushGameRepository.findTop20ByUserOrderByPlayedAtDesc(user).map { game ->
            WordRushGameResponse(
                clientGameId = game.clientGameId,
                score = game.score,
                totalQuestions = game.totalQuestions,
                correctCount = game.correctCount,
                bestStreak = game.bestStreak,
                durationMs = game.durationMs,
                avgResponseMs = game.avgResponseMs,
                grade = game.grade,
                livesRemaining = game.livesRemaining,
                completedNormally = game.completedNormally,
                playedAt = game.playedAt,
            )
        }
    }
}
