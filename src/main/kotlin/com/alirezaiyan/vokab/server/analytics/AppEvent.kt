package com.alirezaiyan.vokab.server.analytics

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity

@Entity
@Table(name = "app_events")
class AppEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    // No FK constraint — records must survive account deletion for analytics integrity
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(name = "event_name", nullable = false, length = 100)
    val eventName: String,

    // JSON string for event-specific properties (package_id, word_count, provider, etc.)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val properties: String? = null,

    @Column(length = 20)
    val platform: String? = null,

    @Column(name = "app_version", length = 30)
    val appVersion: String? = null,

    @Column(name = "client_timestamp", nullable = false)
    val clientTimestamp: Instant,

    @Column(name = "server_timestamp", nullable = false)
    val serverTimestamp: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : JpaEntity<Long>()
