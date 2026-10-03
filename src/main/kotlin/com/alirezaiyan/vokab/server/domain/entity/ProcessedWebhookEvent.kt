package com.alirezaiyan.vokab.server.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Delivery receipt for an external webhook event, so redeliveries are applied once. */
@Entity
@Table(name = "processed_webhook_events")
class ProcessedWebhookEvent(
    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    val eventId: String,

    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant = Instant.now(),
) : JpaEntity<String>() {
    override val id: String get() = eventId
}
