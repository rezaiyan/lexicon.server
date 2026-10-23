package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications/rescue")
class ComebackRescueController(private val service: ComebackRescueService) {

    /** The client finished the rescue session. Idempotent; unknown and foreign ids both 404. */
    @PostMapping("/{rescueId}/complete")
    fun complete(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable rescueId: Long,
    ): ResponseEntity<ApiResponse<Unit>> {
        if (!service.complete(user.id, rescueId)) throw NoSuchElementException("Rescue not found")
        return ResponseEntity.ok(ApiResponse(success = true))
    }
}
