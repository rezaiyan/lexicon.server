package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import com.alirezaiyan.vokab.server.presentation.dto.AvatarResponse
import com.alirezaiyan.vokab.server.presentation.dto.ProfileStatsResponse
import com.alirezaiyan.vokab.server.presentation.dto.UpdateProfileRequest
import com.alirezaiyan.vokab.server.presentation.dto.UserDto
import com.alirezaiyan.vokab.server.service.AuthService
import com.alirezaiyan.vokab.server.service.AvatarService
import com.alirezaiyan.vokab.server.service.ProfileStatsService
import com.alirezaiyan.vokab.server.service.UserService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
    private val authService: AuthService,
    private val featureAccessService: com.alirezaiyan.vokab.server.service.FeatureAccessService,
    private val profileStatsService: ProfileStatsService,
    private val avatarService: AvatarService
) {
    
    @GetMapping("/me")
    fun getCurrentUser(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<UserDto>> {
        return try {
            val userDto = userService.getUserById(user.id!!)
            ResponseEntity.ok(ApiResponse(success = true, data = userDto))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get current user" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get user")))
        }
    }
    
    @PatchMapping("/me")
    fun updateCurrentUser(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<ApiResponse<UserDto>> {
        return try {
            val updated = userService.updateUser(user.id!!, request.name, request.displayAlias)
            ResponseEntity.ok(ApiResponse(success = true, data = updated))
        } catch (e: Exception) {
            logger.error(e) { "Failed to update user" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to update user")))
        }
    }

    @PostMapping("/me/avatar")
    fun uploadAvatar(
        @AuthenticationPrincipal user: User,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ApiResponse<AvatarResponse>> {
        return try {
            val url = avatarService.uploadAvatar(user.id!!, file)
            ResponseEntity.ok(ApiResponse(success = true, data = AvatarResponse(profileImageUrl = url)))
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload avatar" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to upload avatar")))
        }
    }

    @DeleteMapping("/me/avatar")
    fun deleteAvatar(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            avatarService.deleteAvatar(user.id!!)
            ResponseEntity.ok(ApiResponse(success = true, message = "Avatar deleted successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete avatar" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to delete avatar")))
        }
    }
    
    @DeleteMapping("/me")
    fun deleteCurrentUser(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            authService.deleteAccount(user.id!!)
            ResponseEntity.ok(ApiResponse(success = true, message = "Account deleted successfully"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete account" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to delete account")))
        }
    }
    
    /**
     * Get feature flags and user's feature access
     * Returns both global feature flags and user-specific access permissions
     */
    @GetMapping("/feature-access")
    fun getFeatureAccess(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<com.alirezaiyan.vokab.server.presentation.dto.FeatureAccessResponse>> {
        return try {
            val featureFlags = featureAccessService.getClientFeatureFlags()
            val userAccess = featureAccessService.getUserFeatureAccess(user)
            
            val response = com.alirezaiyan.vokab.server.presentation.dto.FeatureAccessResponse(
                featureFlags = featureFlags,
                userAccess = userAccess
            )
            
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get feature access" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get feature access")))
        }
    }
    
    /**
     * Get global feature flags (public endpoint - no auth required)
     */
    @GetMapping("/feature-flags")
    fun getFeatureFlags(): ResponseEntity<ApiResponse<com.alirezaiyan.vokab.server.service.ClientFeatureFlags>> {
        return try {
            val featureFlags = featureAccessService.getClientFeatureFlags()
            ResponseEntity.ok(ApiResponse(success = true, data = featureFlags))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get feature flags" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get feature flags")))
        }
    }

    @GetMapping("/profile-stats")
    fun getProfileStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<ProfileStatsResponse>> {
        return try {
            val stats = profileStatsService.getProfileStats(user)
            ResponseEntity.ok(ApiResponse(success = true, data = stats))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get profile stats" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get profile stats")))
        }
    }
}

