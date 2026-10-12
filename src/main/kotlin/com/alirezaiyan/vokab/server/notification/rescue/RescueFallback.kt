package com.alirezaiyan.vokab.server.notification.rescue

/** Copy for a rescue, from the AI or a template. */
data class RescueCopy(
    val angle: String,
    val title: String,
    val body: String,
    val outroWin: String?,
    val outroSoft: String?,
)

/**
 * The rescue without AI: the surest near-mastery words around the weak words closest to cracking,
 * and plain template copy. Used when the model is unavailable or its answer fails validation.
 */
object RescueFallback {
    private const val MAX_WEAK = 2

    /** Requires [RescuePool.canPlan]. Two easy wins, the weak words, then the remaining easy wins. */
    fun picks(pool: RescuePool): List<Long> {
        val weak = pool.weak.take(MAX_WEAK)
        val near = pool.nearMastery
            .sortedWith(compareByDescending<RescueCandidateRow> { it.recentAccuracy }.thenByDescending { it.level }.thenBy { it.wordId })
            .take(RescueFactSheetBuilder.SESSION_SIZE - weak.size)
        return (near.take(2) + weak + near.drop(2)).map { it.wordId }
    }

    fun copy(pool: RescuePool, picks: List<Long>): RescueCopy {
        val weakWord = pool.weak.first { it.wordId in picks }.text
        val title = "$weakWord wants a rematch".takeIf { RescueCopyLinter.graphemes(it) <= RescueCopyLinter.TITLE_MAX }
            ?: "A rematch is waiting"
        return RescueCopy(
            angle = "rematch",
            title = title,
            body = "${picks.size} words, about ${RescueFactSheetBuilder.EST_MINUTES} min. Most of them you already know.",
            outroWin = "$weakWord: cracked. Nice comeback.",
            outroSoft = "${picks.size} words back in rotation. $weakWord will come round again soon.",
        )
    }
}
