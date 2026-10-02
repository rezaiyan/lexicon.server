package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatWebhookEvent
import com.alirezaiyan.vokab.server.security.RevenueCatWebhookAuthenticator
import com.alirezaiyan.vokab.server.security.WebhookAuthResult
import com.alirezaiyan.vokab.server.service.SubscriptionService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController(
    private val subscriptionService: SubscriptionService,
    private val webhookAuthenticator: RevenueCatWebhookAuthenticator,
    private val objectMapper: ObjectMapper
) {

    /**
     * RevenueCat webhook. Authenticated by [RevenueCatWebhookAuthenticator] (static Authorization
     * header, plus the HMAC signature when signing is configured). The body is read as raw bytes
     * because the signature covers them exactly as sent.
     */
    @PostMapping("/revenuecat")
    fun handleRevenueCatWebhook(
        @RequestBody body: ByteArray,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(RevenueCatWebhookAuthenticator.SIGNATURE_HEADER, required = false) signature: String?
    ): ResponseEntity<ApiResponse<Unit>> {
        val auth = webhookAuthenticator.authenticate(authorization, signature, body)
        if (auth is WebhookAuthResult.Rejected) {
            logger.warn { "Rejected RevenueCat webhook: ${auth.reason}" }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse(success = false, message = "Unauthorized"))
        }

        val event = try {
            objectMapper.readValue(body, RevenueCatWebhookEvent::class.java)
        } catch (e: Exception) {
            logger.warn(e) { "Invalid RevenueCat webhook payload" }
            return ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = "Invalid payload"))
        }

        return try {
            subscriptionService.handleRevenueCatWebhook(event)
            ResponseEntity.ok(ApiResponse(success = true, message = "Webhook processed successfully"))
        } catch (e: Exception) {
            // Non-2xx makes RevenueCat retry with backoff.
            logger.error(e) { "Failed to process RevenueCat webhook type=${event.event.type}" }
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse(success = false, message = "Failed to process webhook"))
        }
    }
}
