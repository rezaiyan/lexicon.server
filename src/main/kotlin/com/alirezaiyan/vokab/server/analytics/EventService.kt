package com.alirezaiyan.vokab.server.analytics

import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import com.alirezaiyan.vokab.server.notification.NotificationEngagementService

private val logger = KotlinLogging.logger {}

@Service
class EventService(
    private val appEventRepository: AppEventRepository,
    private val objectMapper: ObjectMapper,
    private val notificationEngagementService: NotificationEngagementService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional
    fun track(userId: Long, request: TrackEventRequest) {
        try {
            val propertiesJson = if (request.properties.isEmpty()) null
                                 else runCatching { objectMapper.writeValueAsString(request.properties) }.getOrNull()

            val event = AppEvent(
                userId = userId,
                eventName = request.eventName,
                properties = propertiesJson,
                platform = request.platform,
                appVersion = request.appVersion,
                clientTimestamp = Instant.ofEpochMilli(request.clientTimestampMs),
            )
            appEventRepository.save(event)

            if (request.eventName == "notification_opened") {
                runCatching {
                    val logId = request.properties["notification_log_id"]?.toLongOrNull()
                    if (logId != null) {
                        notificationEngagementService.recordOpen(userId, logId)
                    }
                }.onFailure { e -> logger.warn(e) { "Failed to process notification_opened hook for user $userId" } }
            }

            logger.debug { "Tracked event '${request.eventName}' for user $userId" }
        } catch (e: Exception) {
            meterRegistry.counter("app_events.insert_failures").increment()
            logger.warn(e) { "Failed to track event '${request.eventName}' for user $userId" }
        }
    }

    @Async
    fun trackAsync(userId: Long, eventName: String, properties: Map<String, String> = emptyMap()) {
        runCatching {
            track(
                userId,
                TrackEventRequest(
                    eventName = eventName,
                    properties = properties,
                    platform = null,
                    appVersion = null,
                    clientTimestampMs = Instant.now(clock).toEpochMilli(),
                )
            )
        }.onFailure { e -> logger.warn(e) { "Failed to track async event '$eventName' for user $userId" } }
    }
}
