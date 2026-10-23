package com.alirezaiyan.vokab.server.support

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import com.alirezaiyan.vokab.server.shared.consumeOrThrow
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/support")
class SupportController(
    private val supportService: SupportService,
    private val rateLimitConfig: RateLimitConfig,
) {
    @GetMapping("/messages")
    fun getMessages(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "0") after: Long,
    ): ResponseEntity<ApiResponse<SupportMessagesDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = supportService.getMessages(user.id, after)))

    @PostMapping("/messages")
    fun sendMessage(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SendSupportMessageRequest,
        @RequestHeader("X-Platform", required = false) platform: String?,
        @RequestHeader("X-App-Version", required = false) appVersion: String?,
    ): ResponseEntity<ApiResponse<SupportMessageDto>> {
        rateLimitConfig.getSupportBucket(user.id).consumeOrThrow()
        val message = supportService.sendUserMessage(user.id, request, SupportClientInfo(platform, appVersion))
        return ResponseEntity.ok(ApiResponse(success = true, data = message))
    }

    @PostMapping("/read")
    fun markRead(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ApiResponse<Unit>> {
        supportService.markRead(user.id)
        return ResponseEntity.ok(ApiResponse(success = true))
    }
}

@RestController
@RequestMapping("/admin/support")
class SupportAdminController(private val supportService: SupportService) {

    @GetMapping("/threads")
    fun listThreads(): ResponseEntity<ApiResponse<List<SupportThreadSummaryDto>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = supportService.listThreads()))

    @GetMapping("/threads/{threadId}")
    fun getThread(@PathVariable threadId: Long): ResponseEntity<ApiResponse<SupportThreadDetailDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = supportService.getThread(threadId)))

    @PostMapping("/threads/{threadId}/messages")
    fun reply(
        @PathVariable threadId: Long,
        @Valid @RequestBody request: AdminSupportReplyRequest,
    ): ResponseEntity<ApiResponse<SupportMessageDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = supportService.replyFromDashboard(threadId, request)))

    @PostMapping("/threads/{threadId}/status")
    fun setStatus(
        @PathVariable threadId: Long,
        @RequestBody request: SupportStatusRequest,
    ): ResponseEntity<ApiResponse<SupportThreadSummaryDto>> =
        ResponseEntity.ok(ApiResponse(success = true, data = supportService.setStatus(threadId, request.status)))
}

/**
 * Telegram bot webhook (public, like every path under /api/v1/webhooks). Telegram proves itself with the
 * secret registered through `setWebhook`; anything accepted answers 200 so Telegram does not retry.
 */
@RestController
@RequestMapping("/api/v1/webhooks/telegram")
class TelegramWebhookController(
    private val supportService: SupportService,
    private val appProperties: AppProperties,
) {
    @PostMapping
    fun onUpdate(
        @RequestHeader(SECRET_HEADER, required = false) secret: String?,
        @RequestBody update: TelegramUpdate,
    ): ResponseEntity<Unit> {
        if (!secretMatches(secret)) {
            logger.warn { "Telegram webhook rejected: bad or missing secret" }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        supportService.handleTelegramUpdate(update)
        return ResponseEntity.ok().build()
    }

    private fun secretMatches(secret: String?): Boolean {
        val expected = appProperties.notifications.admin.telegram.webhookSecret
        if (expected.isBlank() || secret == null) return false
        return MessageDigest.isEqual(expected.toByteArray(), secret.toByteArray())
    }

    private companion object {
        const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"
    }
}
