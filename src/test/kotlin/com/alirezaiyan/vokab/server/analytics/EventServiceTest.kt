package com.alirezaiyan.vokab.server.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.alirezaiyan.vokab.server.shared.DomainEvent
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher
import org.junit.jupiter.api.assertThrows

class EventServiceTest {

    private lateinit var appEventRepository: AppEventRepository
    private lateinit var objectMapper: ObjectMapper
    private val published = mutableListOf<DomainEvent>()
    private val publisher = object : DomainEventPublisher {
        override fun publish(event: DomainEvent) {
            published += event
        }
    }
    private lateinit var eventService: EventService
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        appEventRepository = mockk()
        objectMapper = ObjectMapper()
        eventService = EventService(appEventRepository, objectMapper, publisher, clock = fixedClock(), meterRegistry = meterRegistry)
    }

    @Test
    fun `track should save event to repository`() {
        // Arrange
        val request = createTrackEventRequest(
            eventName = "word_added",
            properties = mapOf("word" to "hello")
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        // Act
        eventService.track(userId = 1L, request = request)

        // Assert
        verify(exactly = 1) { appEventRepository.save(match { it.eventName == "word_added" && it.userId == 1L }) }
    }

    @Test
    fun `track should handle empty properties`() {
        // Arrange
        val request = createTrackEventRequest(
            eventName = "session_start",
            properties = emptyMap()
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        // Act
        eventService.track(userId = 2L, request = request)

        // Assert
        verify(exactly = 1) { appEventRepository.save(match { it.properties == null && it.eventName == "session_start" }) }
    }

    @Test
    fun `track publishes a notification open when eventName is notification_opened`() {
        val request = createTrackEventRequest(
            eventName = "notification_opened",
            properties = mapOf("notification_log_id" to "99")
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        eventService.track(userId = 1L, request = request)

        assertEquals(listOf(NotificationOpenedEvent(1L, 99L, TEST_NOW)), published)
    }

    @Test
    fun `track publishes nothing when notification_log_id property is missing`() {
        val request = createTrackEventRequest(eventName = "notification_opened", properties = emptyMap())
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        eventService.track(userId = 1L, request = request)

        assertEquals(emptyList<DomainEvent>(), published)
    }

    @Test
    fun `track should not throw when repository throws`() {
        // Arrange
        val request = createTrackEventRequest(eventName = "word_added")
        every { appEventRepository.save(any<AppEvent>()) } throws RuntimeException("DB unavailable")

        // Act & Assert
        assertDoesNotThrow {
            eventService.track(userId = 1L, request = request)
        }
        assertEquals(1.0, meterRegistry.counter("app_events.insert_failures").count())
    }

    @Test
    fun `record saves the event at the given time`() {
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        eventService.record(5L, "subscription_started", mapOf("product_id" to "p1"), TEST_NOW)

        verify(exactly = 1) {
            appEventRepository.save(match { it.eventName == "subscription_started" && it.userId == 5L && it.clientTimestamp == TEST_NOW })
        }
    }

    @Test
    fun `record propagates a failed insert so the event listener is retried`() {
        every { appEventRepository.save(any<AppEvent>()) } throws RuntimeException("connection refused")

        assertThrows<RuntimeException> { eventService.record(1L, "some_event", emptyMap(), TEST_NOW) }
    }

    // --- Factory functions ---

    private fun createTrackEventRequest(
        eventName: String = "test_event",
        properties: Map<String, String> = emptyMap(),
        platform: String? = "ios",
        appVersion: String? = "1.0.0"
    ): TrackEventRequest = TrackEventRequest(
        eventName = eventName,
        properties = properties,
        platform = platform,
        appVersion = appVersion,
        clientTimestampMs = TEST_NOW.toEpochMilli()
    )
}
