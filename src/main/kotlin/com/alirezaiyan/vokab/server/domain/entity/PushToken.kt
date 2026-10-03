package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "push_tokens",
    indexes = [Index(name = "idx_push_token", columnList = "token")]
)
class PushToken(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    
    @Column(nullable = false, unique = true)
    val token: String,
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val platform: Platform,
    
    @Column(name = "device_id")
    val deviceId: String? = null,
    
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
    
    @Column(nullable = false)
    val active: Boolean = true
) : JpaEntity<Long>()

enum class Platform {
    ANDROID,
    IOS,
    WEB
}

