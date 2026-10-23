package com.alirezaiyan.vokab.server.shared

/**
 * Abstraction over event publishing. Decouples domain services from Spring.
 * Swap implementation for tests or to migrate to a message broker.
 */
interface DomainEventPublisher {
    fun publish(event: DomainEvent)
}
