package com.alirezaiyan.vokab.server.auth

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity

@Entity
@Table(name = "audit_log")
class AuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    val eventType: AuditEventType,

    // No @ManyToOne — user_id is a plain column so audit records survive account deletion
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column
    val email: String? = null,

    @Column(name = "ip_address")
    val ipAddress: String? = null,

    @Column(name = "user_agent")
    val userAgent: String? = null,

    // JSON string for event-specific data (family_id, reason, provider, etc.)
    @Column(columnDefinition = "TEXT")
    val metadata: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) : JpaEntity<Long>() {
    override fun toString(): String =
        "AuditLog(id=$id, eventType=$eventType, userId=$userId, createdAt=$createdAt)"
}

enum class AuditEventType {
    LOGIN,
    TOKEN_REFRESH,
    LOGOUT,
    LOGOUT_ALL,
    TOKEN_REVOCATION,
    TOKEN_REUSE,
    ACCOUNT_DELETION,
}
