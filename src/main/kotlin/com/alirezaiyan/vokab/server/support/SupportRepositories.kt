package com.alirezaiyan.vokab.server.support

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface SupportThreadRepository : JpaRepository<SupportThread, Long> {
    fun findByUserId(userId: Long): SupportThread?

    /** Creates the user's thread unless it exists; safe when two first messages race. */
    @Modifying
    @Query(
        value = "INSERT INTO support_threads (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("userId") userId: Long): Int

    @Query("SELECT t FROM SupportThread t JOIN FETCH t.user ORDER BY t.lastMessageAt DESC")
    fun findAllWithUserByRecent(): List<SupportThread>
}

@Repository
interface SupportMessageRepository : JpaRepository<SupportMessage, Long> {
    fun findByThreadIdAndIdGreaterThanOrderByIdAsc(threadId: Long, afterId: Long): List<SupportMessage>

    fun findByThreadIdAndClientMessageId(threadId: Long, clientMessageId: String): SupportMessage?

    @Query("SELECT m FROM SupportMessage m JOIN FETCH m.thread WHERE m.telegramMessageId = :telegramMessageId")
    fun findByTelegramMessageId(@Param("telegramMessageId") telegramMessageId: Long): SupportMessage?

    fun existsByTelegramMessageId(telegramMessageId: Long): Boolean

    fun findFirstByThreadIdOrderByIdDesc(threadId: Long): SupportMessage?

    fun countByThreadIdAndSenderAndReadAtIsNull(threadId: Long, sender: SupportSender): Long

    @Modifying
    @Query(
        "UPDATE SupportMessage m SET m.readAt = :now " +
            "WHERE m.thread.id = :threadId AND m.sender = :sender AND m.readAt IS NULL"
    )
    fun markRead(
        @Param("threadId") threadId: Long,
        @Param("sender") sender: SupportSender,
        @Param("now") now: Instant,
    ): Int
}
