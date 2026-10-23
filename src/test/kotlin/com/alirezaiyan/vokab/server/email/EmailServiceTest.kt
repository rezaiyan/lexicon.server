package com.alirezaiyan.vokab.server.email

import com.alirezaiyan.vokab.server.fixedClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmailServiceTest {

    private val emailProvider: EmailProvider = mockk()
    private val emailLogRepository: EmailLogRepository = mockk()
    private val subscriptionRepository: EmailSubscriptionRepository = mockk()
    private val templateRepository: EmailTemplateRepository = mockk()
    private val sent = slot<EmailSendRequest>()
    private lateinit var service: EmailService

    @BeforeEach
    fun setUp() {
        service = EmailService(
            emailProvider, EmailConfig(enabled = true), emailLogRepository, subscriptionRepository,
            templateRepository, fixedClock(),
        )
        every { templateRepository.findByIdAndActiveTrue("welcome") } returns testTemplate()
        every { subscriptionRepository.findByUserIdAndCategory(any(), any()) } returns null
        every { emailLogRepository.existsByUserIdAndTemplateIdAndCreatedAtAfter(any(), any(), any()) } returns false
        every { emailLogRepository.save(any()) } answers { firstArg() }
        every { emailProvider.send(capture(sent)) } returns EmailSendResult("id-1", "test")
    }

    @Test
    fun `template variables are HTML-escaped in the HTML body`() {
        service.sendTemplated(1L, "a@example.com", "welcome", mapOf("name" to "<script>x</script>"))

        assertEquals("<p>Hi &lt;script&gt;x&lt;/script&gt;</p>", sent.captured.bodyHtml)
    }

    @Test
    fun `template variables can't break the subject onto a new header line`() {
        service.sendTemplated(1L, "a@example.com", "welcome", mapOf("name" to "Bob\r\nBcc: victim@example.com"))

        assertEquals("Welcome Bob Bcc: victim@example.com", sent.captured.subject)
    }

    @Test
    fun `subscribing to an unknown category is rejected`() {
        val subscriptions = EmailSubscriptionService(subscriptionRepository, fixedClock())

        assertThrows<IllegalArgumentException> { subscriptions.subscribe(1L, "anything-goes") }
    }

    private fun testTemplate() = EmailTemplate(
        id = "welcome",
        name = "Welcome",
        subject = "Welcome {{name}}",
        bodyHtml = "<p>Hi {{name}}</p>",
        category = "product_updates",
    )
}
