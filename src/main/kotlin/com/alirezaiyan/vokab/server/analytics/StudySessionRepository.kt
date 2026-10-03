package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*
import com.alirezaiyan.vokab.server.shared.STARTED_AT_TS

@Repository
interface StudySessionRepository : JpaRepository<StudySession, Long> {

    fun findByUser(user: User): List<StudySession>

    fun findByUserAndClientSessionId(user: User, clientSessionId: String): Optional<StudySession>

    fun findByUserOrderByStartedAtDesc(user: User, pageable: Pageable): List<StudySession>

    @Query("SELECT COUNT(s) FROM StudySession s WHERE s.user = :user")
    fun countByUser(user: User): Long

    @Query("SELECT COALESCE(SUM(s.durationMs), 0) FROM StudySession s WHERE s.user = :user")
    fun totalStudyTimeByUser(user: User): Long

    @Query(
        value = """SELECT COUNT(DISTINCT CAST(${STARTED_AT_TS} AT TIME ZONE 'UTC' AS DATE))
           FROM study_sessions WHERE user_id = :userId""",
        nativeQuery = true
    )
    fun countDistinctStudyDays(@Param("userId") userId: Long): Long

    @Query(
        """SELECT s FROM StudySession s
           WHERE s.user = :user AND s.startedAt >= :startMs AND s.startedAt <= :endMs
           ORDER BY s.startedAt"""
    )
    fun findByUserAndDateRange(user: User, startMs: Long, endMs: Long): List<StudySession>

    @Query("SELECT COUNT(s) FROM StudySession s WHERE s.user = :user AND s.completedNormally = false")
    fun countAbandonedByUser(user: User): Long
}
