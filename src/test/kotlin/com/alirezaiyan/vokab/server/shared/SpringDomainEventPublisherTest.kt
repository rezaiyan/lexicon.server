package com.alirezaiyan.vokab.server.shared

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class SpringDomainEventPublisherTest {

    private val applicationEventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
    private val publisher = SpringDomainEventPublisher(applicationEventPublisher)

    @Test
    fun `publish should delegate to ApplicationEventPublisher`() {
        val event = UserSignedUpEvent(
            userId = 1L,
            name = "Ali",
            email = "ali@example.com",
            provider = "google"
        )

        publisher.publish(event)

        verify(exactly = 1) { applicationEventPublisher.publishEvent(event) }
    }
}
