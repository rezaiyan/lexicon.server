package com.alirezaiyan.vokab.server.shared

import java.time.Instant

/**
 * Marker interface for all domain events.
 * Domain events are plain data — no framework dependency.
 */
sealed interface DomainEvent {
    val occurredAt: Instant
}

data class UserSignedUpEvent(
    val userId: Long,
    val name: String,
    val email: String,
    val provider: String,
    val platform: String? = null,
    val country: String? = null,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

data class UserSignedInEvent(
    val userId: Long,
    val name: String,
    val email: String,
    val provider: String,
    val platform: String? = null,
    val country: String? = null,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
