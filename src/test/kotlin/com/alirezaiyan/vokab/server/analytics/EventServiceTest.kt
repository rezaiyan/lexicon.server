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
import com.alirezaiyan.vokab.server.notification.NotificationEngagementService

class EventServiceTest {

    private lateinit var appEventRepository: AppEventRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var notificationEngagementService: NotificationEngagementService
    private lateinit var eventService: EventService
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        appEventRepository = mockk()
        objectMapper = ObjectMapper()
        notificationEngagementService = mockk()
        eventService = EventService(appEventRepository, objectMapper, notificationEngagementService, clock = fixedClock(), meterRegistry = meterRegistry)
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
    fun `track should record notification open when eventName is notification_opened`() {
        // Arrange
        val logId = 42L
        val request = createTrackEventRequest(
            eventName = "notification_opened",
            properties = mapOf("notification_log_id" to logId.toString())
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()
        every { notificationEngagementService.recordOpen(any(), any()) } returns true

        // Act
        eventService.track(userId = 1L, request = request)

        // Assert
        verify(exactly = 1) { notificationEngagementService.recordOpen(1L, logId) }
    }

    @Test
    fun `track should not fail when notification open processing fails`() {
        // Arrange
        val request = createTrackEventRequest(
            eventName = "notification_opened",
            properties = mapOf("notification_log_id" to "99")
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()
        every { notificationEngagementService.recordOpen(any(), any()) } throws RuntimeException("engagement service down")

        // Act & Assert — track must not propagate the exception
        assertDoesNotThrow {
            eventService.track(userId = 1L, request = request)
        }
    }

    @Test
    fun `track should not call engagement service when notification_log_id property is missing`() {
        // Arrange
        val request = createTrackEventRequest(
            eventName = "notification_opened",
            properties = emptyMap()
        )
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        // Act
        eventService.track(userId = 1L, request = request)

        // Assert
        verify(exactly = 0) { notificationEngagementService.recordOpen(any(), any()) }
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
    fun `trackAsync should save event through track internally`() {
        // Arrange
        every { appEventRepository.save(any<AppEvent>()) } returns mockk()

        // Act
        eventService.trackAsync(userId = 5L, eventName = "streak_updated", properties = mapOf("streak" to "7"))

        // Assert — the internal save must have been called once
        verify(exactly = 1) { appEventRepository.save(match { it.eventName == "streak_updated" && it.userId == 5L }) }
    }

    @Test
    fun `trackAsync should not throw when track fails`() {
        // Arrange
        every { appEventRepository.save(any<AppEvent>()) } throws RuntimeException("connection refused")

        // Act & Assert
        assertDoesNotThrow {
            eventService.trackAsync(userId = 1L, eventName = "some_event")
        }
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
