package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "notification_log")
class NotificationLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,                             // no FK intentionally

    @Column(name = "notification_type", nullable = false)
    val notificationType: String,

    @Column(name = "title")
    val title: String? = null,

    @Column(name = "body", columnDefinition = "TEXT")
    val body: String? = null,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant = Instant.now(),

    @Column(name = "opened_at")
    var openedAt: Instant? = null,

    @Column(name = "data_payload", columnDefinition = "jsonb")
    val dataPayload: String? = null              // JSON string
) : JpaEntity<Long>() {
    override fun isTransient(): Boolean = id == 0L
}
