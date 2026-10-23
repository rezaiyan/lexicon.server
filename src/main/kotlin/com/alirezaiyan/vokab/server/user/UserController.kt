package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.subscription.FeatureAccessResponse
import com.alirezaiyan.vokab.server.study.ProfileStatsResponse
import com.alirezaiyan.vokab.server.auth.AccountDeletionService
import com.alirezaiyan.vokab.server.subscription.ClientFeatureFlags
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.study.ProfileStatsService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val accountDeletionService: AccountDeletionService,
    private val featureAccessService: FeatureAccessService,
    private val profileStatsService: ProfileStatsService,
    private val avatarService: AvatarService
) {
    
    @GetMapping("/me")
    fun getCurrentUser(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<UserDto>> {
        val userDto = userService.getUserById(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = userDto))
    }
    
    @PatchMapping("/me")
    fun updateCurrentUser(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ApiResponse<UserDto>> {
        val updated = userService.updateUser(user.id, request.name, request.displayAlias)
        return ResponseEntity.ok(ApiResponse(success = true, data = updated))
    }

    @PostMapping("/me/avatar")
    fun uploadAvatar(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<AvatarResponse>> {
        val url = avatarService.uploadAvatar(user.id, file)
        return ResponseEntity.ok(ApiResponse(success = true, data = AvatarResponse(profileImageUrl = url)))
    }

    @DeleteMapping("/me/avatar")
    fun deleteAvatar(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        avatarService.deleteAvatar(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Avatar deleted successfully"))
    }
    
    @DeleteMapping("/me")
    fun deleteCurrentUser(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<Unit>> {
        accountDeletionService.deleteAccount(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
    }
    
    /**
     * Get feature flags and user's feature access
     * Returns both global feature flags and user-specific access permissions
     */
    @GetMapping("/feature-access")
    fun getFeatureAccess(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<FeatureAccessResponse>> {
        return ResponseEntity.ok(ApiResponse(success = true, data = featureAccessService.getFeatureAccess(user.id)))
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
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<ProfileStatsResponse>> {
        val stats = profileStatsService.getProfileStats(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }
}

