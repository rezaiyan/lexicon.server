package com.alirezaiyan.vokab.server.user

import jakarta.persistence.*
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.JpaEntity

@Entity
@Table(
    name = "user_platforms",
    uniqueConstraints = [UniqueConstraint(name = "uq_user_platform", columnNames = ["user_id", "platform"])],
    indexes = [Index(name = "idx_user_platforms_user_id", columnList = "user_id")]
)
class UserPlatform(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val platform: Platform,

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    val firstSeenAt: Instant = Instant.now(),

    @Column(name = "last_seen_at", nullable = false)
    val lastSeenAt: Instant = Instant.now(),

    @Column(name = "app_version")
    val appVersion: String? = null
) : JpaEntity<Long>()
