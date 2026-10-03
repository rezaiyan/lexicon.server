package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.security.AuthUser
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.SettingsDto
import com.alirezaiyan.vokab.server.service.UserSettingsService
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
}


