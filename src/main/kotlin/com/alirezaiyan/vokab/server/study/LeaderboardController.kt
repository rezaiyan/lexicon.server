package com.alirezaiyan.vokab.server.study

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/leaderboard")
class LeaderboardController(
    private val leaderboardService: LeaderboardService
) {

    @GetMapping
    fun getLeaderboard(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<LeaderboardResponse>> {
        val response = leaderboardService.getLeaderboard(user.id, limit.coerceIn(1, 100))
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }
}
