package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/** notification_log type for pushes an admin sent by hand. */
const val MANUAL_NOTIFICATION_TYPE = "MANUAL"

/**
 * Admin-composed pushes to chosen users or everyone reachable. Each delivered push gets a
 * notification_log row (type [MANUAL_NOTIFICATION_TYPE]) whose id rides in the payload, so opens
 * are tracked like automatic notifications. Deliberately not transactional: every send is
 * Firebase I/O.
 */
@Service
class ManualPushService(
    private val pushTokenRepository: PushTokenRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val pushNotificationService: PushNotificationService,
    private val notificationEngagementService: NotificationEngagementService,
    private val meterRegistry: MeterRegistry,
) {
    fun send(request: ManualPushRequest): ManualPushResultDto {
        val reserved = request.data.keys.filter { it in RESERVED_DATA_KEYS }
        require(reserved.isEmpty()) { "Data keys set by the server can't be overridden: ${reserved.joinToString()}" }
        val type = request.data["type"]
        require(type !in BLOCKED_TYPES) { "type=$type triggers account or billing behaviour in the app" }

        val targets = resolveTargets(request)
        val optedOut =
            if (request.respectOptOut) userSettingsRepository.findNotificationOptedOutUserIds() else emptySet()
        val reachable = pushTokenRepository.findReachableUserIds().toSet()

        logger.info {
            "Admin manual push: audience=${request.audience} targets=${targets.size} " +
                "dryRun=${request.dryRun} respectOptOut=${request.respectOptOut}"
        }

        val recipients = targets.map { target ->
            val userId = target.userId
            when {
                userId == null -> ManualPushRecipientDto(null, target.label, ManualPushOutcome.NOT_FOUND)
                userId in optedOut -> ManualPushRecipientDto(userId, target.label, ManualPushOutcome.OPTED_OUT)
                userId !in reachable -> ManualPushRecipientDto(userId, target.label, ManualPushOutcome.NO_TOKENS)
                request.dryRun -> ManualPushRecipientDto(userId, target.label, ManualPushOutcome.WOULD_SEND)
                else -> deliver(userId, target.label, request)
            }
        }

        val sent = recipients.count { it.outcome in DELIVERED_OUTCOMES }
        val failed = recipients.count { it.outcome == ManualPushOutcome.FAILED }
        if (!request.dryRun) logger.info { "Admin manual push done: sent=$sent failed=$failed of ${targets.size}" }

        return ManualPushResultDto(
            dryRun = request.dryRun,
            targeted = targets.size,
            sent = sent,
            failed = failed,
            skipped = targets.size - sent - failed,
            recipients = recipients.take(MAX_REPORTED_RECIPIENTS),
            recipientsTruncated = recipients.size > MAX_REPORTED_RECIPIENTS,
        )
    }

    private fun deliver(userId: Long, label: String, request: ManualPushRequest): ManualPushRecipientDto {
        val logId = runCatching {
            notificationEngagementService.saveLog(
                userId, MANUAL_NOTIFICATION_TYPE, request.title, request.body, request.data
            )
        }.onFailure { e ->
            logger.error(e) { "Manual push log persist failed for user=$userId — sending without open tracking" }
        }.getOrNull()

        val data = if (logId != null) request.data + (NOTIFICATION_LOG_ID_KEY to logId.toString()) else request.data
        val results = runCatching {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = request.title,
                body = request.body,
                data = data,
                imageUrl = request.imageUrl?.takeIf { it.isNotBlank() },
            )
        }.getOrElse { e ->
            logger.error(e) { "Manual push failed for user=$userId" }
            discardLog(logId)
            meterRegistry.counter("notifications.failed", "type", MANUAL_NOTIFICATION_TYPE).increment()
            return ManualPushRecipientDto(userId, label, ManualPushOutcome.FAILED, error = e.message)
        }

        val delivered = results.count { it.success }
        return if (delivered > 0) {
            meterRegistry.counter("notifications.sent", "type", MANUAL_NOTIFICATION_TYPE).increment()
            ManualPushRecipientDto(userId, label, ManualPushOutcome.SENT, devicesReached = delivered)
        } else {
            // An undelivered push left in the log would later count as ignored
            discardLog(logId)
            meterRegistry.counter("notifications.failed", "type", MANUAL_NOTIFICATION_TYPE).increment()
            val outcome = if (results.isEmpty()) ManualPushOutcome.NO_TOKENS else ManualPushOutcome.FAILED
            ManualPushRecipientDto(userId, label, outcome, error = results.firstNotNullOfOrNull { it.error })
        }
    }

    private fun discardLog(logId: Long?) {
        if (logId == null) return
        runCatching { notificationEngagementService.deleteLog(logId) }
            .onFailure { e -> logger.error(e) { "Failed to discard manual notification log=$logId" } }
    }

    private fun resolveTargets(request: ManualPushRequest): List<Target> =
        when (request.audience) {
            ManualPushAudience.ALL ->
                pushTokenRepository.findReachableUserIds().sorted().map { Target(it, "user $it") }
            ManualPushAudience.USERS -> {
                require(request.userIds.isNotEmpty() || request.emails.isNotEmpty()) {
                    "Provide at least one user id or email"
                }
                val byId = request.userIds.distinct().map { id ->
                    Target(id.takeIf { userRepository.existsById(it) }, "user $id")
                }
                val byEmail = request.emails.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { email ->
                    Target(userRepository.findByEmail(email).orElse(null)?.id, email)
                }
                // An id and an email can name the same account: push to it once
                (byId + byEmail).distinctBy { it.userId ?: it.label }
            }
        }

    private data class Target(val userId: Long?, val label: String)

    companion object {
        private const val MAX_REPORTED_RECIPIENTS = 200

        /** Filled in per send: the open-tracking log id, and the category the message builder adds. */
        val RESERVED_DATA_KEYS = setOf(NOTIFICATION_LOG_ID_KEY, "category")

        /** `type` values the app acts on beyond showing the push (see the dashboard cheat sheet). */
        val BLOCKED_TYPES = setOf(
            NotificationEventListeners.BILLING_ISSUE_PUSH_TYPE,
            NotificationEventListeners.SUBSCRIPTION_UPDATED_PUSH_TYPE,
            "account_deleted",
        )
        private val DELIVERED_OUTCOMES = setOf(ManualPushOutcome.SENT, ManualPushOutcome.WOULD_SEND)
    }
}
