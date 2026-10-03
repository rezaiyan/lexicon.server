package com.alirezaiyan.vokab.server.auth

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User

@Entity
@Table(
    name = "refresh_tokens",
    indexes = [
        Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        Index(name = "idx_refresh_tokens_hash", columnList = "token_hash"),
        Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
    ]
)
class RefreshToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    val tokenHash: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    val revoked: Boolean = false
) : JpaEntity<Long>()

