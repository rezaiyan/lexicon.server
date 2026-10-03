package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "email_subscriptions")
class EmailSubscription(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 50)
    val category: String,

    @Column(nullable = false)
    var subscribed: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) : JpaEntity<Long>()
