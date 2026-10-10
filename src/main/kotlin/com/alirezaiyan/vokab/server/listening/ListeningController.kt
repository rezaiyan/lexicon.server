package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/listening")
class ListeningController(
    private val listeningService: ListeningService,
) {

    @PostMapping("/sync")
    fun syncSessions(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SyncListeningRequest,
    ): ResponseEntity<ApiResponse<SyncListeningResponse>> {
        val response = listeningService.syncSessions(user.id, request)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }
}
