package com.alirezaiyan.vokab.server.service.email

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.UUID
import jakarta.mail.Authenticator
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport

private val logger = KotlinLogging.logger {}

/**
 * SMTP email provider. Uses Jakarta Mail (javax.mail successor).
 * Activated when app.email.provider=smtp.
 */
@Component
@ConditionalOnProperty(name = ["app.email.provider"], havingValue = "smtp")
class SmtpEmailProvider(
    private val emailConfig: EmailConfig
) : EmailProvider {

    override val name = "smtp"

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", emailConfig.smtpHost)
            put("mail.smtp.port", emailConfig.smtpPort.toString())
            put("mail.smtp.auth", "true")
            if (emailConfig.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
            }
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }

        Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(emailConfig.smtpUser, emailConfig.smtpPassword)
            }
        })
    }

    override fun send(request: EmailSendRequest): EmailSendResult {
        val from = request.from ?: emailConfig.fromAddress
        val messageId = UUID.randomUUID().toString()

        logger.info { "Sending email via SMTP: ${request.subject}" }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(MimeMessage.RecipientType.TO, InternetAddress.parse(request.to))
            subject = request.subject
            request.replyTo?.let { setReplyTo(InternetAddress.parse(it)) }
                ?: emailConfig.replyTo.takeIf { it.isNotBlank() }?.let { setReplyTo(InternetAddress.parse(it)) }

            if (request.bodyText != null) {
                val multipart = jakarta.mail.internet.MimeMultipart("alternative")
                val textPart = jakarta.mail.internet.MimeBodyPart().apply {
                    setText(request.bodyText, "utf-8")
                }
                val htmlPart = jakarta.mail.internet.MimeBodyPart().apply {
                    setContent(request.bodyHtml, "text/html; charset=utf-8")
                }
                multipart.addBodyPart(textPart)
                multipart.addBodyPart(htmlPart)
                setContent(multipart)
            } else {
                setContent(request.bodyHtml, "text/html; charset=utf-8")
            }
        }

        Transport.send(message)
        logger.info { "Email sent via SMTP: id=$messageId" }
        return EmailSendResult(providerId = messageId, provider = name)
    }
}
