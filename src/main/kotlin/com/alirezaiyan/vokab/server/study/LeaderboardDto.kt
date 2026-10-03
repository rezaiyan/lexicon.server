package com.alirezaiyan.vokab.server.study

data class LeaderboardEntryDto(
    val rank: Int,
    val displayName: String,
    val currentStreak: Int,
    val longestStreak: Int,
    val masteredWords: Int,
    val isCurrentUser: Boolean,
    val profileImageUrl: String? = null
)

data class LeaderboardResponse(
    val entries: List<LeaderboardEntryDto>,
    val userEntry: LeaderboardEntryDto?
)
