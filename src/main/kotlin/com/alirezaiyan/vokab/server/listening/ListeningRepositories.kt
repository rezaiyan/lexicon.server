package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ListeningSessionRepository : JpaRepository<ListeningSession, Long> {
    fun existsByUserAndClientSessionId(user: User, clientSessionId: String): Boolean
    fun findByUser(user: User): List<ListeningSession>
    fun countByUser(user: User): Long

    /** Epoch millis of the user's latest session start, or null if they never listened. */
    @Query("SELECT MAX(s.startedAt) FROM ListeningSession s WHERE s.user.id = :userId")
    fun findLastStartedAt(@Param("userId") userId: Long): Long?
}

@Repository
interface ListeningSessionWordRepository : JpaRepository<ListeningSessionWord, Long> {
    fun findByUser(user: User): List<ListeningSessionWord>
    fun findBySession(session: ListeningSession): List<ListeningSessionWord>
}
