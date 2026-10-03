package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.TrackEventRequest
import com.alirezaiyan.vokab.server.service.EventService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import com.alirezaiyan.vokab.server.domain.entity.requireId

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventService: EventService,
) {
    @PostMapping
    fun trackEvent(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: TrackEventRequest,
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            eventService.track(user.requireId(), request)
            ResponseEntity.ok(ApiResponse(success = true, message = "Event tracked"))
        } catch (e: Exception) {
            // Analytics must never break the client — absorb all errors silently
            logger.warn(e) { "Failed to track event '${request.eventName}' for user ${user.id}" }
            ResponseEntity.ok(ApiResponse(success = true, message = "Event accepted"))
        }
    }
}
