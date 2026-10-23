package com.alirezaiyan.vokab.server.wordrush

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/word-rush")
class WordRushController(
    private val wordRushService: WordRushService,
) {

    @PostMapping("/sync")
    fun syncGames(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SyncWordRushRequest,
    ): ResponseEntity<ApiResponse<SyncWordRushResponse>> {
        val response = wordRushService.syncGames(user.id, request)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: AuthUser,
    ): ResponseEntity<ApiResponse<WordRushInsightsResponse>> {
        val insights = wordRushService.getInsights(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = insights))
    }

    @GetMapping("/history")
    fun getHistory(
        @AuthenticationPrincipal user: AuthUser,
    ): ResponseEntity<ApiResponse<List<WordRushGameResponse>>> {
        val history = wordRushService.getHistory(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = history))
    }
}
