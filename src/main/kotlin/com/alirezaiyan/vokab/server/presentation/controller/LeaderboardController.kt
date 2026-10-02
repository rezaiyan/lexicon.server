package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.LeaderboardResponse
import com.alirezaiyan.vokab.server.service.LeaderboardService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/leaderboard")
class LeaderboardController(
    private val leaderboardService: LeaderboardService
) {

    @GetMapping
    fun getLeaderboard(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<LeaderboardResponse>> {
        return try {
            val response = leaderboardService.getLeaderboard(user, limit.coerceIn(1, 100))
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get leaderboard" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get leaderboard")))
        }
    }
}
