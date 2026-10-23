package com.alirezaiyan.vokab.server.email

/**
 * Abstraction over email delivery providers (Resend, SES, SMTP, etc.).
 * Implement this interface to plug in any provider.
 */
interface EmailProvider {
    val name: String

    /**
     * Send an email and return the provider's message ID.
     * Throws on failure.
     */
    fun send(request: EmailSendRequest): EmailSendResult
}

data class EmailSendRequest(
    val to: String,
    val subject: String,
    val bodyHtml: String,
    val bodyText: String? = null,
    val from: String? = null,
    val replyTo: String? = null,
    val tags: Map<String, String> = emptyMap()
)

data class EmailSendResult(
    val providerId: String,
    val provider: String
)
