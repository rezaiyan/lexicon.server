package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.WordRushService
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
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: SyncWordRushRequest,
    ): ResponseEntity<ApiResponse<SyncWordRushResponse>> {
        val response = wordRushService.syncGames(user, request)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ApiResponse<WordRushInsightsResponse>> {
        val insights = wordRushService.getInsights(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = insights))
    }

    @GetMapping("/history")
    fun getHistory(
        @AuthenticationPrincipal user: User,
    ): ResponseEntity<ApiResponse<List<WordRushGameResponse>>> {
        val history = wordRushService.getHistory(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = history))
    }
}
