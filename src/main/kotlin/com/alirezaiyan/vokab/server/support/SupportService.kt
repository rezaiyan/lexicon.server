package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.notification.PushNotificationService
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.user.User
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

private val logger = KotlinLogging.logger {}

/** Push `type` telling the app a support reply arrived; the app refreshes the chat. */
const val SUPPORT_REPLY_PUSH_TYPE = "support_reply"

/** Device context the app sends with a message (from `X-Platform` / `X-App-Version`). */
data class SupportClientInfo(val platform: String?, val appVersion: String?)

/**
 * In-app support chat. User messages are stored, then relayed to the admin Telegram chat; the
 * admin answers there (Telegram reply) or from the dashboard, and the user gets a push.
 *
 * Not @Transactional: relaying and pushing are network I/O, so only the DB steps run in
 * short transactions. A failed relay or push never fails the stored message.
 */
@Service
class SupportService(
    private val threadRepository: SupportThreadRepository,
    private val messageRepository: SupportMessageRepository,
    private val relay: SupportRelay,
    private val pushNotificationService: PushNotificationService,
    private val appProperties: AppProperties,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val writeTx = TransactionTemplate(transactionManager)
    private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun getMessages(userId: Long, afterId: Long): SupportMessagesDto = read {
        val thread = threadRepository.findByUserId(userId)
            ?: return@read SupportMessagesDto(emptyList(), 0)
        val threadId = checkNotNull(thread.id)
        SupportMessagesDto(
            messages = messageRepository.findByThreadIdAndIdGreaterThanOrderByIdAsc(threadId, afterId)
                .map { it.toDto() },
            unreadCount = messageRepository.countByThreadIdAndSenderAndReadAtIsNull(threadId, SupportSender.ADMIN),
        )
    }

    fun sendUserMessage(
        userId: Long,
        request: SendSupportMessageRequest,
        client: SupportClientInfo,
    ): SupportMessageDto {
        val body = request.body.trim()
        require(body.isNotEmpty()) { "Message must not be empty" }

        val (message, isNew, relayText) = write {
            threadRepository.insertIfAbsent(userId)
            val thread = checkNotNull(threadRepository.findByUserId(userId))
            val threadId = checkNotNull(thread.id)
            val existing = request.clientMessageId
                ?.let { messageRepository.findByThreadIdAndClientMessageId(threadId, it) }
            if (existing != null) return@write Triple(existing, false, "")

            val now = clock.instant()
            thread.status = SupportThreadStatus.OPEN
            thread.lastMessageAt = now
            val saved = messageRepository.save(
                SupportMessage(
                    thread = thread,
                    sender = SupportSender.USER,
                    body = body,
                    clientMessageId = request.clientMessageId,
                    createdAt = now,
                )
            )
            Triple(saved, true, formatUserMessage(threadId, thread.user, client, body))
        }

        if (isNew) linkToRelay(message, relay.post(relayText))
        return message.toDto()
    }

    fun markRead(userId: Long) {
        write {
            val thread = threadRepository.findByUserId(userId) ?: return@write
            messageRepository.markRead(checkNotNull(thread.id), SupportSender.ADMIN, clock.instant())
        }
    }

    /**
     * Telegram webhook update. Only a text reply, in the admin chat, to a relayed support
     * message counts; everything else is ignored. Retried updates are stored once.
     */
    fun handleTelegramUpdate(update: TelegramUpdate) {
        val message = update.message ?: return
        val text = message.text?.trim().orEmpty()
        val repliedTo = message.replyToMessage?.messageId
        if (text.isEmpty() || repliedTo == null || message.from?.isBot == true) return
        if (message.chat?.id?.toString() != appProperties.notifications.admin.telegram.chatId) return

        val stored = write {
            if (messageRepository.existsByTelegramMessageId(message.messageId)) return@write null
            val thread = messageRepository.findByTelegramMessageId(repliedTo)?.thread ?: return@write null
            appendAdminMessage(thread, text, telegramMessageId = message.messageId)
        } ?: return

        relay.acknowledge(message.messageId)
        notifyUser(stored.recipientId, stored.message.body)
    }

    fun listThreads(): List<SupportThreadSummaryDto> = read {
        threadRepository.findAllWithUserByRecent().map { it.toSummary(messageRepository.lastOf(it)) }
    }

    fun getThread(threadId: Long): SupportThreadDetailDto = read {
        val thread = findThread(threadId)
        SupportThreadDetailDto(
            thread = thread.toSummary(messageRepository.lastOf(thread)),
            messages = messageRepository.findByThreadIdAndIdGreaterThanOrderByIdAsc(threadId, 0).map { it.toDto() },
        )
    }

    /** A reply typed in the admin dashboard; mirrored to Telegram so the chat history stays whole. */
    fun replyFromDashboard(threadId: Long, request: AdminSupportReplyRequest): SupportMessageDto {
        val body = request.body.trim()
        require(body.isNotEmpty()) { "Reply must not be empty" }
        val stored = write { appendAdminMessage(findThread(threadId), body, telegramMessageId = null) }
        linkToRelay(stored.message, relay.post("↩️ Dashboard reply to support #$threadId:\n\n$body"))
        notifyUser(stored.recipientId, stored.message.body)
        return stored.message.toDto()
    }

    fun setStatus(threadId: Long, status: SupportThreadStatus): SupportThreadSummaryDto = write {
        val thread = findThread(threadId)
        thread.status = status
        thread.toSummary(messageRepository.lastOf(thread))
    }

    private class AdminMessage(val message: SupportMessage, val recipientId: Long)

    private fun appendAdminMessage(thread: SupportThread, body: String, telegramMessageId: Long?): AdminMessage {
        val now = clock.instant()
        thread.lastMessageAt = now
        val saved = messageRepository.save(
            SupportMessage(
                thread = thread,
                sender = SupportSender.ADMIN,
                body = body.take(MAX_SUPPORT_MESSAGE_LENGTH),
                telegramMessageId = telegramMessageId,
                createdAt = now,
            )
        )
        return AdminMessage(saved, checkNotNull(thread.user.id))
    }

    /** Remembers which Telegram message carries [message], so a reply to it finds the thread. */
    private fun linkToRelay(message: SupportMessage, telegramMessageId: Long?) {
        if (telegramMessageId == null) return
        write {
            messageRepository.findById(checkNotNull(message.id)).ifPresent { it.telegramMessageId = telegramMessageId }
        }
    }

    private fun notifyUser(userId: Long, body: String) {
        runCatching {
            pushNotificationService.sendNotificationToUser(
                userId = userId,
                title = PUSH_TITLE,
                body = body.take(PUSH_PREVIEW_LENGTH),
                data = mapOf("type" to SUPPORT_REPLY_PUSH_TYPE),
            )
        }.onFailure { e -> logger.warn(e) { "Support reply push failed for user=$userId" } }
    }

    private fun findThread(threadId: Long): SupportThread =
        threadRepository.findById(threadId).orElseThrow { NoSuchElementException("Support thread not found") }

    @Suppress("UNCHECKED_CAST") // execute() is typed T? only because the callback may return null
    private fun <T> read(block: () -> T): T = readTx.execute { block() } as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> write(block: () -> T): T = writeTx.execute { block() } as T

    private companion object {
        const val PUSH_TITLE = "Lexicon Support"
        const val PUSH_PREVIEW_LENGTH = 140
    }
}

internal fun SupportThread.toSummary(last: SupportMessage?) = SupportThreadSummaryDto(
    id = checkNotNull(id),
    userId = checkNotNull(user.id),
    userLabel = user.supportLabel(),
    status = status,
    lastMessagePreview = last?.body?.take(SUMMARY_PREVIEW_LENGTH),
    lastSender = last?.sender,
    lastMessageAt = lastMessageAt.toEpochMilli(),
)

private const val SUMMARY_PREVIEW_LENGTH = 120

private fun SupportMessageRepository.lastOf(thread: SupportThread): SupportMessage? =
    findFirstByThreadIdOrderByIdDesc(checkNotNull(thread.id))

/** The admin chat's view of a user message: who (masked), which device, what they wrote. */
internal fun formatUserMessage(threadId: Long, user: User, client: SupportClientInfo, body: String): String {
    val device = listOfNotNull(client.platform, client.appVersion).joinToString(" ").ifBlank { "unknown device" }
    return "💬 Support #$threadId · ${user.supportLabel()}\n$device\n\n$body\n\n↩️ Reply to this message to answer"
}

/** Display name and masked email: all the admin side ever sees of the user. */
internal fun User.supportLabel(): String = "${displayAlias?.takeIf { it.isNotBlank() } ?: name} (${maskEmail(email)})"

/** `alireza@gmail.com` → `al***@gmail.com`. */
internal fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    val local = email.substring(0, at)
    val visible = if (local.length > 2) local.take(2) else local.take(1)
    return "$visible***${email.substring(at)}"
}
