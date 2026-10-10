package com.alirezaiyan.vokab.server.wordrush

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WordRushGameRepository : JpaRepository<WordRushGame, Long> {

    fun existsByUserAndClientGameId(user: User, clientGameId: String): Boolean

    @Query(
        value = """
            SELECT
                COUNT(*)                                                                            AS totalGames,
                COALESCE(SUM(CASE WHEN g.completed_normally THEN 1 ELSE 0 END), 0)                 AS totalCompleted,
                COALESCE(MAX(g.best_streak), 0)                                                     AS bestStreakEver,
                COALESCE(AVG(CAST(g.score AS DOUBLE PRECISION)), 0.0)                                  AS avgScore,
                COALESCE(AVG(CAST(g.correct_count AS DOUBLE PRECISION) / NULLIF(g.total_questions, 0) * 100), 0.0)
                                                                                                    AS avgAccuracyPercent,
                COALESCE(SUM(g.duration_ms), 0)                                                     AS totalTimePlayedMs,
                COALESCE(AVG(CAST(g.avg_response_ms AS DOUBLE PRECISION)), 0.0)                     AS avgResponseMs
            FROM word_rush_games g
            WHERE g.user_id = :userId
        """,
        nativeQuery = true
    )
    fun findInsightsByUserId(@Param("userId") userId: Long): WordRushInsightsProjection

    fun findTop20ByUserOrderByPlayedAtDesc(user: User): List<WordRushGame>

    fun countByUser(user: User): Long

    fun findByUser(user: User): List<WordRushGame>

    @Query("SELECT COALESCE(MAX(g.score), 0) FROM WordRushGame g WHERE g.user = :user")
    fun findBestScore(@Param("user") user: User): Int
}

interface WordRushInsightsProjection {
    val totalGames: Long
    val totalCompleted: Long
    val bestStreakEver: Int
    val avgScore: Double
    val avgAccuracyPercent: Double
    val totalTimePlayedMs: Long
    val avgResponseMs: Double
}
