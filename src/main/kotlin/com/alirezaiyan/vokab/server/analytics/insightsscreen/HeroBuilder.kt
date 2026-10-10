package com.alirezaiyan.vokab.server.analytics.insightsscreen

object HeroBuilder {

    private const val HISTORY_WEEKS = 12L

    fun build(snapshot: LearnerSnapshot): HeroDto {
        val thisWeek = snapshot.reviewsIn(snapshot.thisWeek())
        val lastWeek = snapshot.reviewsIn(snapshot.lastWeek())
        val byDate = snapshot.reviewsByDate()
        val currentAccuracy = thisWeek.gatedAccuracyPct()

        return HeroDto(
            headline = headline(snapshot, thisWeek.size, lastWeek.size),
            reviews = MetricDto(thisWeek.size, lastWeek.size),
            accuracyPct = currentAccuracy?.let { MetricDto(it, lastWeek.accuracyPct() ?: 0) },
            leveledUp = MetricDto(thisWeek.leveledUpWordCount(), lastWeek.leveledUpWordCount()),
            currentStreak = snapshot.currentStreak,
            week = (0L..6L).map { offset ->
                val date = snapshot.weekStart.plusDays(offset)
                DayCountDto(date.toString(), byDate[date] ?: 0)
            },
        )
    }

    private fun headline(snapshot: LearnerSnapshot, thisWeek: Int, lastWeek: Int): String {
        if (thisWeek == 0) return "A fresh week to learn"
        val pastWeeks = (1L until HISTORY_WEEKS).map { back ->
            val start = snapshot.weekStart.minusWeeks(back)
            snapshot.reviewsIn(Period(start, start.plusDays(6))).size
        }
        return when {
            thisWeek > pastWeeks.max() -> "Your best week in $HISTORY_WEEKS weeks"
            thisWeek > lastWeek -> "Ahead of last week"
            else -> "Keep the rhythm going"
        }
    }
}
