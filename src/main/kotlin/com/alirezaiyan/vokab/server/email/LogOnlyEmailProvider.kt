package com.alirezaiyan.vokab.server.email

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * No-op provider that just logs emails. Default when no provider is configured.
 * Useful for development and testing.
 */
@Component
@ConditionalOnProperty(name = ["app.email.provider"], havingValue = "log", matchIfMissing = true)
class LogOnlyEmailProvider : EmailProvider {

    override val name = "log"

    override fun send(request: EmailSendRequest): EmailSendResult {
        val id = UUID.randomUUID().toString()
        logger.info {
            "[EMAIL-LOG] to=${request.to} subject='${request.subject}' html=${request.bodyHtml.length} chars"
        }
        return EmailSendResult(providerId = id, provider = name)
    }
}
