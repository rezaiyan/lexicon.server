package com.alirezaiyan.vokab.server.user

data class UserDto(
    val id: Long,
    val email: String,
    val name: String,
    val subscriptionStatus: SubscriptionStatus,
    val subscriptionExpiresAt: String?,
    val currentStreak: Int = 0,
    val displayAlias: String? = null,
    val profileImageUrl: String? = null
)
