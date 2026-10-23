package com.alirezaiyan.vokab.server.study

data class ProgressStatsDto(
    val totalWords: Int,
    val dueCards: Int,
    val level0Count: Int,
    val level1Count: Int,
    val level2Count: Int,
    val level3Count: Int,
    val level4Count: Int,
    val level5Count: Int,
    val level6Count: Int
)
