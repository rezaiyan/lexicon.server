package com.alirezaiyan.vokab.server.analytics

import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher

private val logger = KotlinLogging.logger {}

@Service
class EventService(
    private val appEventRepository: AppEventRepository,
    private val objectMapper: ObjectMapper,
    private val domainEventPublisher: DomainEventPublisher,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    /** Client-reported events: best effort, a failed insert never fails the request. */
    @Transactional
    fun track(userId: Long, request: TrackEventRequest) {
        try {
            record(
                userId, request.eventName, request.properties,
                Instant.ofEpochMilli(request.clientTimestampMs), request.platform, request.appVersion,
            )
            if (request.eventName == "notification_opened") {
                request.properties["notification_log_id"]?.toLongOrNull()?.let { logId ->
                    domainEventPublisher.publish(NotificationOpenedEvent(userId, logId, Instant.now(clock)))
                }
            }
            logger.debug { "Tracked event '${request.eventName}' for user $userId" }
        } catch (e: Exception) {
            meterRegistry.counter("app_events.insert_failures").increment()
            logger.warn(e) { "Failed to track event '${request.eventName}' for user $userId" }
        }
    }

    /**
     * Stores one event and throws on failure, so a server-side event listener that calls it is
     * retried by the event publication registry instead of silently losing the row.
     */
    @Transactional
    fun record(
        userId: Long,
        eventName: String,
        properties: Map<String, String>,
        at: Instant,
        platform: String? = null,
        appVersion: String? = null,
    ) {
        val propertiesJson = if (properties.isEmpty()) null else objectMapper.writeValueAsString(properties)
        appEventRepository.save(
            AppEvent(
                userId = userId,
                eventName = eventName,
                properties = propertiesJson,
                platform = platform,
                appVersion = appVersion,
                clientTimestamp = at,
            )
        )
    }
}
