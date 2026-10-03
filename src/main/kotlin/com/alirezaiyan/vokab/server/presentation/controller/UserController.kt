package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.AvatarResponse
import com.alirezaiyan.vokab.server.presentation.dto.FeatureAccessResponse
import com.alirezaiyan.vokab.server.presentation.dto.ProfileStatsResponse
import com.alirezaiyan.vokab.server.presentation.dto.UpdateProfileRequest
import com.alirezaiyan.vokab.server.presentation.dto.UserDto
import com.alirezaiyan.vokab.server.service.AuthService
import com.alirezaiyan.vokab.server.service.AvatarService
import com.alirezaiyan.vokab.server.service.ClientFeatureFlags
import com.alirezaiyan.vokab.server.service.FeatureAccessService
import com.alirezaiyan.vokab.server.service.ProfileStatsService
import com.alirezaiyan.vokab.server.service.UserService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val authService: AuthService,
    private val featureAccessService: FeatureAccessService,
    private val profileStatsService: ProfileStatsService,
    private val avatarService: AvatarService
) {
    
    @GetMapping("/me")
    fun getCurrentUser(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<UserDto>> {
        val userDto = userService.getUserById(user.requireId())
        return ResponseEntity.ok(ApiResponse(success = true, data = userDto))
    }
    
    @PatchMapping("/me")
    fun updateCurrentUser(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ApiResponse<UserDto>> {
        val updated = userService.updateUser(user.requireId(), request.name, request.displayAlias)
        return ResponseEntity.ok(ApiResponse(success = true, data = updated))
    }

    @PostMapping("/me/avatar")
    fun uploadAvatar(
        @AuthenticationPrincipal user: User,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<AvatarResponse>> {
        val url = avatarService.uploadAvatar(user.requireId(), file)
        return ResponseEntity.ok(ApiResponse(success = true, data = AvatarResponse(profileImageUrl = url)))
    }

    @DeleteMapping("/me/avatar")
    fun deleteAvatar(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        avatarService.deleteAvatar(user.requireId())
        return ResponseEntity.ok(ApiResponse(success = true, message = "Avatar deleted successfully"))
    }
    
    @DeleteMapping("/me")
    fun deleteCurrentUser(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        authService.deleteAccount(user.requireId())
        return ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
    }
    
    /**
     * Get feature flags and user's feature access
     * Returns both global feature flags and user-specific access permissions
     */
    @GetMapping("/feature-access")
    fun getFeatureAccess(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<FeatureAccessResponse>> {
        return ResponseEntity.ok(ApiResponse(success = true, data = featureAccessService.getFeatureAccess(user.requireId())))
    }
    
    /**
     * Get global feature flags (public endpoint - no auth required)
     */
    @GetMapping("/feature-flags")
    fun getFeatureFlags(): ResponseEntity<ApiResponse<ClientFeatureFlags>> {
        val featureFlags = featureAccessService.getClientFeatureFlags()
        return ResponseEntity.ok(ApiResponse(success = true, data = featureFlags))
    }

    @GetMapping("/profile-stats")
    fun getProfileStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<ProfileStatsResponse>> {
        val stats = profileStatsService.getProfileStats(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }
}

