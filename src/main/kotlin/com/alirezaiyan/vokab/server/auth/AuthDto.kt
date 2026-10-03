package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.UserDto
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class GoogleAuthRequest(
    @field:NotBlank(message = "ID token is required")
    val idToken: String
)

data class AppleAuthRequest(
    @field:NotBlank(message = "ID token is required")
    val idToken: String,
    val authorizationCode: String? = null,
    val fullName: String? = null,
    val appleUserId: String? = null
)

data class RefreshTokenRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto
)
