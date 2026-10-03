package com.alirezaiyan.vokab.server.service.email

import com.alirezaiyan.vokab.server.config.describe
import com.alirezaiyan.vokab.server.exception.UpstreamServiceException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body

private val logger = KotlinLogging.logger {}

/**
 * Email provider using Resend (resend.com).
 * Activated when app.email.provider=resend and API key is set.
 */
@Component
@ConditionalOnProperty(name = ["app.email.provider"], havingValue = "resend")
class ResendEmailProvider(
    private val emailConfig: EmailConfig,
    restClientBuilder: RestClient.Builder,
) : EmailProvider {

    override val name = "resend"

    private val restClient = restClientBuilder
        .baseUrl(BASE_URL)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${emailConfig.apiKey}")
        .build()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ResendResponse(val id: String? = null)

    /** @throws UpstreamServiceException when Resend rejects the message or can't be reached */
    override fun send(request: EmailSendRequest): EmailSendResult {
        val payload = mapOf(
            "from" to (request.from ?: emailConfig.fromAddress),
            "to" to listOf(request.to),
            "subject" to request.subject,
            "html" to request.bodyHtml,
            "text" to request.bodyText,
            "reply_to" to (request.replyTo ?: emailConfig.replyTo),
        ).filterValues { it != null && it != "" }

        val response = try {
            restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body<ResendResponse>()
        } catch (e: RestClientException) {
            throw UpstreamServiceException("Resend send failed: ${e.describe()}", e)
        }

        val id = response?.id ?: throw UpstreamServiceException("Resend response has no message id")
        logger.info { "Email sent via Resend: id=$id" }
        return EmailSendResult(providerId = id, provider = name)
    }

    private companion object {
        const val BASE_URL = "https://api.resend.com"
    }
}
