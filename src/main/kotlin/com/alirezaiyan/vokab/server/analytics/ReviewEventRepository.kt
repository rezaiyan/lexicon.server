package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import com.alirezaiyan.vokab.server.shared.REVIEWED_AT_TS

@Repository
interface ReviewEventRepository : JpaRepository<ReviewEvent, Long> {

    /** Raw review facts since [sinceMs], oldest first. Feeds the zone-aware insights screen. */
    @Query(
        """SELECT e.wordId AS wordId, e.wordText AS wordText, e.rating AS rating,
                  e.previousLevel AS previousLevel, e.newLevel AS newLevel, e.reviewedAt AS reviewedAt
           FROM ReviewEvent e
           WHERE e.user.id = :userId AND e.reviewedAt >= :sinceMs
           ORDER BY e.reviewedAt"""
    )
    fun findFactsByUserIdSince(@Param("userId") userId: Long, @Param("sinceMs") sinceMs: Long): List<ReviewFactProjection>

    fun findByUser(user: User): List<ReviewEvent>

    @Query("SELECT COUNT(e) FROM ReviewEvent e WHERE e.user = :user")
    fun countByUser(user: User): Long

    @Query("SELECT COUNT(e) FROM ReviewEvent e WHERE e.user = :user AND e.rating >= 1")
    fun countCorrectByUser(user: User): Long

    @Query("SELECT COUNT(DISTINCT e.wordId) FROM ReviewEvent e WHERE e.user = :user")
    fun countDistinctWordsReviewed(user: User): Long

    @Query(
        """SELECT COUNT(DISTINCT e.wordId) FROM ReviewEvent e
           WHERE e.user = :user AND e.newLevel = 6 AND e.previousLevel < 6"""
    )
    fun countWordsMastered(user: User): Long

    @Query(
        """SELECT COUNT(DISTINCT e.wordId) FROM ReviewEvent e
           WHERE e.user = :user AND e.newLevel = 6 AND e.previousLevel < 6
           AND e.reviewedAt >= :startMs AND e.reviewedAt <= :endMs"""
    )
    fun countWordsMasteredInRange(user: User, startMs: Long, endMs: Long): Long

    @Query("SELECT AVG(e.responseTimeMs) FROM ReviewEvent e WHERE e.user = :user AND e.responseTimeMs > 0")
    fun getAverageResponseTime(user: User): Double?

    @Query(
        """SELECT AVG(e.responseTimeMs) FROM ReviewEvent e
           WHERE e.user = :user AND e.responseTimeMs > 0
           AND e.reviewedAt >= :startMs AND e.reviewedAt <= :endMs"""
    )
    fun getAverageResponseTimeInRange(user: User, startMs: Long, endMs: Long): Double?

    @Query(
        """SELECT e.wordId AS wordId, e.wordText AS wordText, e.wordTranslation AS wordTranslation,
           e.sourceLanguage AS sourceLanguage, e.targetLanguage AS targetLanguage,
           COUNT(e) AS total, SUM(CASE WHEN e.rating = 0 THEN 1 ELSE 0 END) AS errors
           FROM ReviewEvent e WHERE e.user = :user
           GROUP BY e.wordId, e.wordText, e.wordTranslation, e.sourceLanguage, e.targetLanguage
           HAVING COUNT(e) >= :minReviews
           ORDER BY (SUM(CASE WHEN e.rating = 0 THEN 1 ELSE 0 END) * 1.0 / COUNT(e)) DESC"""
    )
    fun findDifficultWords(user: User, minReviews: Int, pageable: Pageable): List<DifficultWordProjection>

    @Query(
        """SELECT e.wordId AS wordId, e.wordText AS wordText, e.wordTranslation AS wordTranslation,
           COUNT(e) AS total
           FROM ReviewEvent e WHERE e.user = :user
           GROUP BY e.wordId, e.wordText, e.wordTranslation
           ORDER BY COUNT(e) DESC"""
    )
    fun findMostReviewedWords(user: User, pageable: Pageable): List<MostReviewedWordProjection>

    @Query(
        """SELECT e.previousLevel AS level, COUNT(e) AS total,
           SUM(CASE WHEN e.rating >= 1 THEN 1 ELSE 0 END) AS correct
           FROM ReviewEvent e WHERE e.user = :user
           GROUP BY e.previousLevel ORDER BY e.previousLevel"""
    )
    fun getAccuracyByLevel(user: User): List<AccuracyByLevelProjection>

    @Query(
        value = """SELECT CAST(EXTRACT(HOUR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "hour",
           COUNT(*) AS total,
           SUM(CASE WHEN rating >= 1 THEN 1 ELSE 0 END) AS correct
           FROM review_events WHERE user_id = :userId
           GROUP BY CAST(EXTRACT(HOUR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER)
           ORDER BY 1""",
        nativeQuery = true
    )
    fun getAccuracyByHour(@Param("userId") userId: Long): List<HourlyAccuracyProjection>

    @Query(
        value = """SELECT CAST(EXTRACT(DOW FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "dayofweek",
           COUNT(*) AS total,
           SUM(CASE WHEN rating >= 1 THEN 1 ELSE 0 END) AS correct
           FROM review_events WHERE user_id = :userId
           GROUP BY CAST(EXTRACT(DOW FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER)
           ORDER BY 1""",
        nativeQuery = true
    )
    fun getAccuracyByDayOfWeek(@Param("userId") userId: Long): List<DayOfWeekAccuracyProjection>

    @Query(
        value = """SELECT TO_CHAR(${REVIEWED_AT_TS} AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS "day",
           COUNT(*) AS "count"
           FROM review_events WHERE user_id = :userId
           AND reviewed_at >= :startMs AND reviewed_at <= :endMs
           GROUP BY TO_CHAR(${REVIEWED_AT_TS} AT TIME ZONE 'UTC', 'YYYY-MM-DD')
           ORDER BY 1""",
        nativeQuery = true
    )
    fun getHeatmapData(
        @Param("userId") userId: Long,
        @Param("startMs") startMs: Long,
        @Param("endMs") endMs: Long,
    ): List<HeatmapDayProjection>

    @Query(
        """SELECT e.previousLevel AS fromLevel, e.newLevel AS toLevel, COUNT(e) AS count
           FROM ReviewEvent e WHERE e.user = :user
           GROUP BY e.previousLevel, e.newLevel
           ORDER BY e.previousLevel, e.newLevel"""
    )
    fun getLevelTransitions(user: User): List<LevelTransitionProjection>

    @Query(
        """SELECT e.wordId AS wordId, e.wordText AS wordText, e.wordTranslation AS wordTranslation,
           MIN(e.reviewedAt) AS masteredAt
           FROM ReviewEvent e WHERE e.user = :user AND e.newLevel = 6 AND e.previousLevel < 6
           GROUP BY e.wordId, e.wordText, e.wordTranslation
           ORDER BY MIN(e.reviewedAt) DESC"""
    )
    fun findWordsMastered(user: User, pageable: Pageable): List<MasteredWordProjection>

    @Query(
        """SELECT e.sourceLanguage AS sourceLanguage, e.targetLanguage AS targetLanguage,
           COUNT(e) AS total,
           SUM(CASE WHEN e.rating >= 1 THEN 1 ELSE 0 END) AS correct,
           COUNT(DISTINCT e.wordId) AS uniqueWords
           FROM ReviewEvent e WHERE e.user = :user
           GROUP BY e.sourceLanguage, e.targetLanguage
           ORDER BY COUNT(e) DESC"""
    )
    fun getStatsByLanguagePair(user: User): List<LanguagePairStatsProjection>

    @Query(
        value = """SELECT CAST(EXTRACT(YEAR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "yr",
           CAST(EXTRACT(MONTH FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "mo",
           COUNT(*) AS total,
           SUM(CASE WHEN rating >= 1 THEN 1 ELSE 0 END) AS correct
           FROM review_events WHERE user_id = :userId
           GROUP BY CAST(EXTRACT(YEAR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER),
                    CAST(EXTRACT(MONTH FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER)
           ORDER BY 1, 2""",
        nativeQuery = true
    )
    fun getMonthlyStats(@Param("userId") userId: Long): List<MonthlyStatsProjection>

    @Query(
        value = """SELECT CAST(EXTRACT(YEAR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "yr",
           CAST(EXTRACT(WEEK FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER) AS "wk",
           AVG(response_time_ms) AS avgms
           FROM review_events WHERE user_id = :userId
           GROUP BY CAST(EXTRACT(YEAR FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER),
                    CAST(EXTRACT(WEEK FROM ${REVIEWED_AT_TS} AT TIME ZONE 'UTC') AS INTEGER)
           ORDER BY 1, 2""",
        nativeQuery = true
    )
    fun getResponseTimeTrend(@Param("userId") userId: Long): List<ResponseTimeTrendProjection>

    @Query(
        """SELECT DISTINCT e.wordId AS wordId, e.wordText AS wordText, e.wordTranslation AS wordTranslation
           FROM ReviewEvent e WHERE e.user = :user AND e.newLevel = 6
           AND e.wordId IN (SELECT e2.wordId FROM ReviewEvent e2 WHERE e2.user = :user
               AND e2.newLevel <= e2.previousLevel - 2)"""
    )
    fun findComebackWords(user: User): List<ComebackWordProjection>

    @Query(
        value = """SELECT TO_CHAR(${REVIEWED_AT_TS} AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS "day",
           COUNT(DISTINCT word_id) AS "uniqueWords",
           SUM(CASE WHEN new_level > previous_level THEN 1 ELSE 0 END) AS "leveledUp",
           SUM(CASE WHEN new_level < previous_level THEN 1 ELSE 0 END) AS "leveledDown"
           FROM review_events WHERE user_id = :userId
           AND reviewed_at >= :startMs AND reviewed_at <= :endMs
           GROUP BY TO_CHAR(${REVIEWED_AT_TS} AT TIME ZONE 'UTC', 'YYYY-MM-DD')
           ORDER BY 1""",
        nativeQuery = true
    )
    fun getDailyEventStats(
        @Param("userId") userId: Long,
        @Param("startMs") startMs: Long,
        @Param("endMs") endMs: Long,
    ): List<DailyEventStatsProjection>

    @Query(
        value = "SELECT re.reviewed_at FROM review_events re WHERE re.user_id = :userId AND re.reviewed_at >= :sinceMs",
        nativeQuery = true
    )
    fun findReviewedAtByUserIdSince(@Param("userId") userId: Long, @Param("sinceMs") sinceMs: Long): List<Long>
}
