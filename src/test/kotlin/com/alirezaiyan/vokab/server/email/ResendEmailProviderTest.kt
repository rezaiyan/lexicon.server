package com.alirezaiyan.vokab.server.email

import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class ResendEmailProviderTest {

    private val emailsUrl = "https://api.resend.com/emails"
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = ResendEmailProvider(
        EmailConfig(provider = "resend", apiKey = "re_test", fromAddress = "Lexicon <noreply@lexicon.app>"),
        builder,
    )

    @Test
    fun `send posts the message with the API key and returns the Resend id`() {
        server.expect(requestTo(emailsUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test"))
            .andExpect(
                content().json(
                    """{"from":"Lexicon <noreply@lexicon.app>","to":["user@example.com"],
                        "subject":"Hi","html":"<p>Hi</p>","text":"Hi"}"""
                )
            )
            .andRespond(withSuccess("""{"id":"msg_123"}""", MediaType.APPLICATION_JSON))

        val result = provider.send(EmailSendRequest(to = "user@example.com", subject = "Hi", bodyHtml = "<p>Hi</p>", bodyText = "Hi"))

        assertEquals(EmailSendResult(providerId = "msg_123", provider = "resend"), result)
        server.verify()
    }

    @Test
    fun `send omits blank optional fields`() {
        server.expect(requestTo(emailsUrl))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("reply_to"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"text\""))))
            .andRespond(withSuccess("""{"id":"msg_1"}""", MediaType.APPLICATION_JSON))

        provider.send(EmailSendRequest(to = "user@example.com", subject = "Hi", bodyHtml = "<p>Hi</p>"))
        server.verify()
    }

    @Test
    fun `send throws UpstreamServiceException on an error status`() {
        server.expect(requestTo(emailsUrl)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY))

        assertThrows<UpstreamServiceException> {
            provider.send(EmailSendRequest(to = "user@example.com", subject = "Hi", bodyHtml = "<p>Hi</p>"))
        }
    }

    @Test
    fun `send throws UpstreamServiceException when the response has no id`() {
        server.expect(requestTo(emailsUrl)).andRespond(withSuccess("""{}""", MediaType.APPLICATION_JSON))

        assertThrows<UpstreamServiceException> {
            provider.send(EmailSendRequest(to = "user@example.com", subject = "Hi", bodyHtml = "<p>Hi</p>"))
        }
    }
}
