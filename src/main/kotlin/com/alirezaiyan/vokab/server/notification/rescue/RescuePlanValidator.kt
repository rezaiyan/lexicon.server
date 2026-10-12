package com.alirezaiyan.vokab.server.notification.rescue

/** Checks the AI's word picks; anything off and the planner uses [RescueFallback] instead. */
object RescuePlanValidator {
    val SIZE_RANGE = 6..10
    private const val MIN_WEAK = 1
    private const val MIN_NEAR_MASTERY = 4

    /** [ids] unchanged when they form a valid session arc from [pool], otherwise null. */
    fun validPicks(ids: List<Long>, pool: RescuePool): List<Long>? {
        if (ids.size !in SIZE_RANGE || ids.toSet().size != ids.size) return null

        val near = pool.nearMastery.map { it.wordId }.toSet()
        val weak = pool.weak.map { it.wordId }.toSet()
        if (ids.any { it !in near && it !in weak }) return null
        if (ids.count { it in weak } < MIN_WEAK || ids.count { it in near } < MIN_NEAR_MASTERY) return null
        if (ids.first() !in near || ids.last() !in near) return null

        return ids
    }
}
