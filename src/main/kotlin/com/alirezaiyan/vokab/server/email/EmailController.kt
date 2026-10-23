package com.alirezaiyan.vokab.server.email

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/email")
class EmailController(
    private val emailSubscriptionService: EmailSubscriptionService
) {

    @GetMapping("/preferences")
    fun getPreferences(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<EmailPreferenceDto>>> {
        val prefs = emailSubscriptionService.getPreferences(user.id)
            .map { EmailPreferenceDto(category = it.category, subscribed = it.subscribed) }
        return ResponseEntity.ok(ApiResponse(success = true, data = prefs))
    }

    @PostMapping("/subscribe")
    fun subscribe(
        @AuthenticationPrincipal user: AuthUser,
        @RequestBody request: EmailCategoryRequest
    ): ResponseEntity<ApiResponse<EmailPreferenceDto>> {
        val sub = emailSubscriptionService.subscribe(user.id, request.category)
        return ResponseEntity.ok(
            ApiResponse(success = true, data = EmailPreferenceDto(category = sub.category, subscribed = sub.subscribed))
        )
    }

    @PostMapping("/unsubscribe")
    fun unsubscribe(
        @AuthenticationPrincipal user: AuthUser,
        @RequestBody request: EmailCategoryRequest
    ): ResponseEntity<ApiResponse<EmailPreferenceDto>> {
        val sub = emailSubscriptionService.unsubscribe(user.id, request.category)
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
