package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/settings")
class SettingsController(
    private val service: UserSettingsService
) {
    @GetMapping
    fun get(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ApiResponse<SettingsDto>> {
        val dto = service.get(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = dto))
    }

    @PatchMapping
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @RequestBody dto: SettingsDto
    ): ResponseEntity<ApiResponse<SettingsDto>> {
        val updated = service.update(user.id, dto)
        return ResponseEntity.ok(ApiResponse(success = true, data = updated))
    }

    /**
     * The device's timezone, sent by the app on launch and whenever it changes. Separate from
     * PATCH because that replaces every setting: a launch-time sync there would overwrite the
     * server's values with the device's local defaults.
     */
    @PutMapping("/timezone")
    fun updateTimezone(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: UpdateTimezoneRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        service.updateTimezone(user.id, request.timezone)
        return ResponseEntity.ok(ApiResponse(success = true))
    }
}
