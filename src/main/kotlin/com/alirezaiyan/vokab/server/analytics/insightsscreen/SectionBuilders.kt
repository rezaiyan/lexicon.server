package com.alirezaiyan.vokab.server.analytics.insightsscreen

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private val logger = KotlinLogging.logger {}

data class SectionsResult(val sections: SectionsDto, val locked: List<LockedSectionDto>)

object SectionBuilders {

    private const val MAX_HARDEST = 10
    private const val MIN_WEEKDAYS_FOR_CLAIM = 2

    fun build(snapshot: LearnerSnapshot): SectionsResult {
        val locked = mutableListOf<LockedSectionDto>()
        val habitsGap = MIN_REVIEWS_PER_BUCKET - snapshot.reviews.size
        if (habitsGap > 0) locked += LockedSectionDto(SectionKey.HABITS, habitsGap)

        return SectionsResult(
            sections = SectionsDto(
                mastery = isolated("mastery") { mastery(snapshot) },
                habits = if (habitsGap > 0) null else isolated("habits") { habits(snapshot) },
                words = isolated("words") { words(snapshot) },
                wordRush = isolated("wordRush") { wordRush(snapshot) },
            ),
            locked = locked,
        )
    }

    /** Hides a failing section; Errors (OOM, StackOverflow) still propagate. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> isolated(name: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        logger.warn(e) { "Insights section $name failed; hiding it" }
        null
    }

    private fun mastery(snapshot: LearnerSnapshot): MasterySectionDto? {
        if (snapshot.wordsPerLevel.values.sum() == 0L) return null
        val week = snapshot.reviewsIn(snapshot.thisWeek())
        val promoted = week.leveledUpWordCount()
        val demoted = week.demotedWordCount()
        val caption = when {
            promoted > demoted -> "${plural(promoted, "word")} moved up a stage this week"
            demoted > 0 -> "${plural(demoted, "word")} slipped back. A review will catch them up."
            else -> "Your words across the learning stages"
        }
        return MasterySectionDto(
            caption = caption,
            levels = (0..MASTERED_LEVEL).map { LevelCountDto(it, snapshot.wordsPerLevel[it] ?: 0) },
            promotedThisWeek = promoted,
            demotedThisWeek = demoted,
        )
    }

    private fun habits(snapshot: LearnerSnapshot): HabitsSectionDto {
        val bestHour = snapshot.bestHour()?.let { (hour, accuracy) -> BestHourDto(hour, accuracy) }
        val weekdays = snapshot.weekdayBuckets()
            .mapNotNull { (day, facts) ->
                facts.gatedAccuracyPct(MIN_REVIEWS_PER_BUCKET)?.let { WeekdayAccuracyDto(day.value, it, facts.size) }
            }
            .sortedBy { it.isoDay }
        val heatmap = snapshot.reviewsByDate().toSortedMap()
            .map { (date, count) -> DayCountDto(date.toString(), count) }
        val caption = if (weekdays.size >= MIN_WEEKDAYS_FOR_CLAIM) {
            val best = DayOfWeek.of(weekdays.maxBy { it.accuracyPct }.isoDay)
            "You're sharpest on ${best.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}s"
        } else {
            "${plural(heatmap.size, "active day")} in the last 12 weeks"
        }
        return HabitsSectionDto(caption, heatmap, bestHour, weekdays)
    }

    private fun words(snapshot: LearnerSnapshot): WordsSectionDto? {
        val hardest = snapshot.difficultWords.sortedBy { it.accuracyPct }.take(MAX_HARDEST)
            .map { WordAccuracyDto(it.id, it.text, it.accuracyPct) }
        val comebacks = snapshot.comebackWords.map { WordChipDto(it.id, it.text) }
        if (hardest.isEmpty() && comebacks.isEmpty()) return null
        val caption = if (hardest.isNotEmpty()) "Focus here for quick wins" else "Words you turned around"
        return WordsSectionDto(caption, hardest, comebacks)
    }

    private fun wordRush(snapshot: LearnerSnapshot): WordRushSectionDto? {
        val wr = snapshot.wordRush?.takeIf { it.gamesPlayed > 0 } ?: return null
        return WordRushSectionDto("Best score ${wr.bestScore}", wr.gamesPlayed, wr.bestScore, wr.recentScores)
    }
}
