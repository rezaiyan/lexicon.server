package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.Instant

enum class SupportThreadStatus { OPEN, CLOSED }

enum class SupportSender { USER, ADMIN }

@Entity
@Table(name = "support_threads")
class SupportThread(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SupportThreadStatus = SupportThreadStatus.OPEN,

    @Column(name = "last_message_at", nullable = false)
    var lastMessageAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : JpaEntity<Long>() {
    override fun toString(): String = "SupportThread(id=$id, status=$status)"
}

@Entity
@Table(name = "support_messages")
class SupportMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", nullable = false)
    val thread: SupportThread,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val sender: SupportSender,

    @Column(nullable = false)
    val body: String,

    @Column(name = "client_message_id")
    val clientMessageId: String? = null,

    @Column(name = "telegram_message_id")
    var telegramMessageId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "read_at")
    var readAt: Instant? = null,
) : JpaEntity<Long>() {
    // Never print the body: it is user-written content
    override fun toString(): String = "SupportMessage(id=$id, sender=$sender)"
}
