package com.alirezaiyan.vokab.server.user

import jakarta.validation.constraints.Size

data class UpdateProfileRequest(
    @field:Size(max = 100, message = "Name must be at most 100 characters")
    val name: String? = null,

    @field:Size(min = 2, max = 30, message = "Username must be between 2 and 30 characters")
    val displayAlias: String? = null
)

data class AvatarResponse(
    val profileImageUrl: String
)
