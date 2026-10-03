package com.alirezaiyan.vokab.server.service.email

import com.alirezaiyan.vokab.server.domain.entity.EmailLog
import com.alirezaiyan.vokab.server.domain.entity.EmailStatus
import com.alirezaiyan.vokab.server.domain.repository.EmailLogRepository
import com.alirezaiyan.vokab.server.domain.repository.EmailSubscriptionRepository
import com.alirezaiyan.vokab.server.domain.repository.EmailTemplateRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Service
class EmailService(
    private val emailProvider: EmailProvider,
    private val emailConfig: EmailConfig,
    private val emailLogRepository: EmailLogRepository,
    private val emailSubscriptionRepository: EmailSubscriptionRepository,
    private val emailTemplateRepository: EmailTemplateRepository,
    private val clock: Clock
) {

    /**
     * Send an email using a template. Checks subscription preferences and dedup.
     * Returns true if email was sent, false if skipped.
     */
    @Transactional
    fun sendTemplated(
        userId: Long,
        recipientEmail: String,
        templateId: String,
        variables: Map<String, String> = emptyMap(),
        dedupHours: Long = 24
    ): Boolean {
        if (!emailConfig.enabled) {
            logger.debug { "Email disabled, skipping: $templateId for userId=$userId" }
            return false
        }

        val template = emailTemplateRepository.findByIdAndActiveTrue(templateId)
        if (template == null) {
            logger.warn { "Email template not found or inactive: $templateId" }
            return false
        }

        // Check subscription preference
        val subscription = emailSubscriptionRepository.findByUserIdAndCategory(userId, template.category)
        if (subscription != null && !subscription.subscribed) {
            logger.info { "User $userId unsubscribed from ${template.category}, skipping $templateId" }
            return false
        }

        // Dedup: skip if same template was sent recently
        if (dedupHours > 0) {
            val cutoff = Instant.now(clock).minus(dedupHours, ChronoUnit.HOURS)
            if (emailLogRepository.existsByUserIdAndTemplateIdAndCreatedAtAfter(userId, templateId, cutoff)) {
                logger.info { "Dedup: $templateId already sent to user $userId within ${dedupHours}h" }
                return false
            }
        }

        val subject = replaceVariables(template.subject, variables)
        val bodyHtml = replaceVariables(template.bodyHtml, variables)
        val bodyText = template.bodyText?.let { replaceVariables(it, variables) }

        return sendRaw(
            userId = userId,
            recipientEmail = recipientEmail,
            category = template.category,
            templateId = templateId,
            subject = subject,
            bodyHtml = bodyHtml,
            bodyText = bodyText
        )
    }

    /**
     * Send a raw email without template lookup. Still logs and checks enabled flag.
     */
    @Transactional
    fun sendRaw(
        userId: Long? = null,
        recipientEmail: String,
        category: String,
        templateId: String,
        subject: String,
        bodyHtml: String,
        bodyText: String? = null
    ): Boolean {
        if (!emailConfig.enabled) {
            logger.debug { "Email disabled, skipping raw send for userId=$userId" }
            return false
        }

        val log = emailLogRepository.save(
            EmailLog(
                userId = userId,
                recipientEmail = recipientEmail,
                category = category,
                templateId = templateId,
                subject = subject,
                status = EmailStatus.QUEUED
            )
        )

        return try {
            val result = emailProvider.send(
                EmailSendRequest(
                    to = recipientEmail,
                    subject = subject,
                    bodyHtml = bodyHtml,
                    bodyText = bodyText
                )
            )

            log.status = EmailStatus.SENT
            log.provider = result.provider
            log.providerId = result.providerId
            log.sentAt = Instant.now(clock)
            emailLogRepository.save(log)
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send email (log id=${log.id}): ${e.message}" }
            log.status = EmailStatus.FAILED
            log.provider = emailProvider.name
            log.errorMessage = e.message?.take(1000)
            emailLogRepository.save(log)
            false
        }
    }

    @Transactional(readOnly = true)
    fun isSubscribed(userId: Long, category: String): Boolean {
        val sub = emailSubscriptionRepository.findByUserIdAndCategory(userId, category)
        return sub?.subscribed ?: true // Default: subscribed
    }

    @Transactional(readOnly = true)
    fun getSubscribedUserIds(category: String): List<Long> {
        return emailSubscriptionRepository.findByCategoryAndSubscribedTrue(category)
            .map { it.userId }
    }

    private fun replaceVariables(text: String, variables: Map<String, String>): String {
        var result = text
        for ((key, value) in variables) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
