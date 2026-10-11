package com.alirezaiyan.vokab.server.support

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

const val MAX_SUPPORT_MESSAGE_LENGTH = 4000

data class SendSupportMessageRequest(
    @field:NotBlank
    @field:Size(max = MAX_SUPPORT_MESSAGE_LENGTH)
    val body: String,
    /** Client-generated id, so a retried send stores the message once. */
    @field:Size(max = 64)
    val clientMessageId: String? = null,
)

data class SupportMessageDto(
    val id: Long,
    val sender: SupportSender,
    val body: String,
    val clientMessageId: String?,
    /** Epoch millis. */
    val createdAt: Long,
)

data class SupportMessagesDto(
    val messages: List<SupportMessageDto>,
    /** Admin replies the user has not opened yet. */
    val unreadCount: Long,
)

data class AdminSupportReplyRequest(
    @field:NotBlank
    @field:Size(max = MAX_SUPPORT_MESSAGE_LENGTH)
    val body: String,
)

data class SupportStatusRequest(val status: SupportThreadStatus)

data class SupportThreadSummaryDto(
    val id: Long,
    val userId: Long,
    val userLabel: String,
    val status: SupportThreadStatus,
    val lastMessagePreview: String?,
    val lastSender: SupportSender?,
    /** Epoch millis. */
    val lastMessageAt: Long,
)

data class SupportThreadDetailDto(
    val thread: SupportThreadSummaryDto,
    val messages: List<SupportMessageDto>,
)

/** The subset of a Telegram Update the support webhook reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id") val updateId: Long = 0,
    val message: TelegramMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id") val messageId: Long = 0,
    val chat: TelegramChat? = null,
    val from: TelegramUser? = null,
    val text: String? = null,
    @JsonProperty("reply_to_message") val replyToMessage: TelegramMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(val id: Long = 0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(@JsonProperty("is_bot") val isBot: Boolean = false)

internal fun SupportMessage.toDto() = SupportMessageDto(
    id = checkNotNull(id),
    sender = sender,
    body = body,
    clientMessageId = clientMessageId,
    createdAt = createdAt.toEpochMilli(),
)
