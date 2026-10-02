package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.RevenueCatWebhookEvent
import com.alirezaiyan.vokab.server.service.SubscriptionService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController(
    private val subscriptionService: SubscriptionService,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    /**
     * RevenueCat webhook. RevenueCat does not sign payloads; it sends the static
     * `Authorization` header value configured in its dashboard, which must equal
     * `app.revenuecat.webhook-secret` (optionally prefixed with `Bearer `).
     */
    @PostMapping("/revenuecat")
    fun handleRevenueCatWebhook(
        @RequestBody body: String,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
    ): ResponseEntity<ApiResponse<Unit>> {
        val secret = appProperties.revenuecat.webhookSecret
        if (secret.isNotBlank() && !isAuthorized(authorization, secret)) {
            logger.warn { "Rejected RevenueCat webhook: missing or invalid Authorization header" }
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

    private fun isAuthorized(header: String?, secret: String): Boolean {
        val provided = header?.trim()?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(provided.toByteArray(), secret.toByteArray())
    }
}
