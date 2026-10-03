package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.security.AuthUser
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.RecordActivityRequest
import com.alirezaiyan.vokab.server.presentation.dto.StreakResponse
import com.alirezaiyan.vokab.server.service.StreakService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/streak")
class StreakController(
    private val streakService: StreakService
) {

    /**
     * Record user activity (called when review section opens)
     * POST /api/v1/streak/record
     */
    @PostMapping("/record")
    fun recordActivity(
        @AuthenticationPrincipal user: AuthUser,
        @RequestBody(required = false) request: RecordActivityRequest?
    ): ResponseEntity<ApiResponse<StreakResponse>> {
        val count = request?.count ?: 1
        logger.debug { "Recording activity for userId=${user.id}, count=$count" }
        val updatedUser = streakService.recordActivity(user.id, count)
        return ResponseEntity.ok(
            ApiResponse(success = true, data = StreakResponse(currentStreak = updatedUser.currentStreak), message = "Activity recorded successfully")
        )
    }

    /**
     * Get user's current streak
     * GET /api/v1/streak
     */
    @GetMapping
    fun getStreak(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<StreakResponse>> {
        val streakInfo = streakService.getUserStreak(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = StreakResponse(currentStreak = streakInfo.currentStreak)))
    }
}
