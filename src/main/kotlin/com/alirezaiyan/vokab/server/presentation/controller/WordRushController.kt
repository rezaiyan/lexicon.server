package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.WordRushService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/word-rush")
class WordRushController(
    private val wordRushService: WordRushService,
) {

    @PostMapping("/sync")
    fun syncGames(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: SyncWordRushRequest,
    ): ResponseEntity<ApiResponse<SyncWordRushResponse>> {
        return try {
            val response = wordRushService.syncGames(user, request)
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync Word Rush games" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to sync")))
        }
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ApiResponse<WordRushInsightsResponse>> {
        return try {
            val insights = wordRushService.getInsights(user)
            ResponseEntity.ok(ApiResponse(success = true, data = insights))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Word Rush insights" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get insights")))
        }
    }

    @GetMapping("/history")
    fun getHistory(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ApiResponse<List<WordRushGameResponse>>> {
        return try {
            val history = wordRushService.getHistory(user)
            ResponseEntity.ok(ApiResponse(success = true, data = history))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get Word Rush history" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get history")))
        }
    }
}
