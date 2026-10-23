package com.alirezaiyan.vokab.server.shared

import java.time.Instant

/**
 * Marker for domain events: plain data, published through [DomainEventPublisher] and handled by
 * `@ApplicationModuleListener`s after the publishing transaction commits. Each event lives in the
 * module that publishes it; the user lifecycle events below are shared by several modules.
 */
interface DomainEvent {
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
