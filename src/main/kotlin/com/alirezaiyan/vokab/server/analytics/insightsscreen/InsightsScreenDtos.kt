package com.alirezaiyan.vokab.server.analytics.insightsscreen

import com.fasterxml.jackson.annotation.JsonInclude

data class InsightsScreenResponse(
    val generatedAt: String,
    val totalReviews: Long,
    val hero: HeroDto,
    val coach: List<CoachCardDto>,
    val sections: SectionsDto,
    val locked: List<LockedSectionDto>,
)

data class MetricDto(val value: Int, val previous: Int)

data class DayCountDto(val date: String, val reviews: Int)

data class HeroDto(
    val headline: String,
    val reviews: MetricDto,
    /** Null below [MIN_REVIEWS_FOR_PERCENT] reviews this week. */
    val accuracyPct: MetricDto?,
    val leveledUp: MetricDto,
    val currentStreak: Int,
    /** Monday … Sunday of the current local week; future days have 0 reviews. */
    val week: List<DayCountDto>,
)

data class WordChipDto(val id: Long, val text: String)

object CoachActionKind {
    const val REVIEW_WORDS = "REVIEW_WORDS"
    const val START_REVIEW = "START_REVIEW"
    const val ENABLE_REMINDER = "ENABLE_REMINDER"
    const val START_WORD_RUSH = "START_WORD_RUSH"
    const val NONE = "NONE"
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CoachActionDto(
    val kind: String,
    val label: String? = null,
    val wordIds: List<Long> = emptyList(),
    val hour: Int? = null,
)

data class CoachCardDto(
    /** Stable per type per local day; the client uses it for dismissal. */
    val id: String,
    val type: String,
    val priority: Int,
    val title: String,
    val body: String,
    val words: List<WordChipDto>,
    val moreWordsCount: Int,
    val action: CoachActionDto,
)

data class SectionsDto(
    val mastery: MasterySectionDto?,
    val habits: HabitsSectionDto?,
    val words: WordsSectionDto?,
    val wordRush: WordRushSectionDto?,
)

data class LevelCountDto(val level: Int, val words: Long)

data class MasterySectionDto(
    val caption: String,
    val levels: List<LevelCountDto>,
    val promotedThisWeek: Int,
    val demotedThisWeek: Int,
)

data class BestHourDto(val hour: Int, val accuracyPct: Int)

data class WeekdayAccuracyDto(val isoDay: Int, val accuracyPct: Int, val reviews: Int)

data class HabitsSectionDto(
    val caption: String,
    /** Only days with at least one review; the client fills gaps. */
    val heatmap: List<DayCountDto>,
    val bestHour: BestHourDto?,
    val weekdays: List<WeekdayAccuracyDto>,
)

data class WordAccuracyDto(val id: Long, val text: String, val accuracyPct: Int)

data class WordsSectionDto(
    val caption: String,
    val hardest: List<WordAccuracyDto>,
    val comebacks: List<WordChipDto>,
)

data class WordRushSectionDto(
    val caption: String,
    val gamesPlayed: Long,
    val bestScore: Int,
    /** Oldest first, at most 5. */
    val recentScores: List<Int>,
)

object SectionKey {
    const val HABITS = "habits"
}

data class LockedSectionDto(val section: String, val reviewsNeeded: Int)
