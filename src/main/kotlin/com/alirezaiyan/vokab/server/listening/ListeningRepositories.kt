package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ListeningSessionRepository : JpaRepository<ListeningSession, Long> {
    fun existsByUserAndClientSessionId(user: User, clientSessionId: String): Boolean
    fun findByUser(user: User): List<ListeningSession>
    fun countByUser(user: User): Long
}

@Repository
interface ListeningSessionWordRepository : JpaRepository<ListeningSessionWord, Long> {
    fun findByUser(user: User): List<ListeningSessionWord>
    fun findBySession(session: ListeningSession): List<ListeningSessionWord>
}
