package com.alirezaiyan.vokab.server.study

data class StreakResponse(
    val currentStreak: Int
)

data class RecordActivityRequest(
    val count: Int = 1
)


