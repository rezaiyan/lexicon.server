package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.service.email.EmailSubscriptionService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import com.alirezaiyan.vokab.server.domain.entity.requireId

@RestController
@RequestMapping("/api/v1/email")
class EmailController(
    private val emailSubscriptionService: EmailSubscriptionService
) {

    @GetMapping("/preferences")
    fun getPreferences(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<EmailPreferenceDto>>> {
        val prefs = emailSubscriptionService.getPreferences(user.requireId())
            .map { EmailPreferenceDto(category = it.category, subscribed = it.subscribed) }
        return ResponseEntity.ok(ApiResponse(success = true, data = prefs))
    }

    @PostMapping("/subscribe")
    fun subscribe(
        @AuthenticationPrincipal user: User,
        @RequestBody request: EmailCategoryRequest
    ): ResponseEntity<ApiResponse<EmailPreferenceDto>> {
        val sub = emailSubscriptionService.subscribe(user.requireId(), request.category)
        return ResponseEntity.ok(
            ApiResponse(success = true, data = EmailPreferenceDto(category = sub.category, subscribed = sub.subscribed))
        )
    }

    @PostMapping("/unsubscribe")
    fun unsubscribe(
        @AuthenticationPrincipal user: User,
        @RequestBody request: EmailCategoryRequest
    ): ResponseEntity<ApiResponse<EmailPreferenceDto>> {
        val sub = emailSubscriptionService.unsubscribe(user.requireId(), request.category)
        return ResponseEntity.ok(
            ApiResponse(success = true, data = EmailPreferenceDto(category = sub.category, subscribed = sub.subscribed))
        )
    }
}

data class EmailPreferenceDto(
    val category: String,
    val subscribed: Boolean
)

data class EmailCategoryRequest(
    val category: String
)
