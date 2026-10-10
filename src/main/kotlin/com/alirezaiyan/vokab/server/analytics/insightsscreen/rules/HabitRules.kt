package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.*
import org.springframework.stereotype.Component

@Component
class StreakAtRiskRule : CoachRule {
    override val type = "STREAK_AT_RISK"
    override val priority = 100

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        if (snapshot.currentStreak < 1 || snapshot.now.hour < EVENING_HOUR) return null
        val byDate = snapshot.reviewsByDate()
        val studiedToday = (byDate[snapshot.today] ?: 0) > 0
        val studiedYesterday = (byDate[snapshot.today.minusDays(1)] ?: 0) > 0
        if (studiedToday || !studiedYesterday) return null
        return card(
            snapshot,
            title = "Keep your ${snapshot.currentStreak}-day streak alive",
            body = "A few minutes of review today keeps it going.",
            action = CoachActionDto(CoachActionKind.START_REVIEW, label = "Review now"),
        )
    }

    companion object {
        const val EVENING_HOUR = 18
    }
}

@Component
class BestTimeRule : CoachRule {
    override val type = "BEST_TIME"
    override val priority = 60

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        if (snapshot.remindersEnabled) return null
        val overall = snapshot.reviews.gatedAccuracyPct() ?: return null
        val (hour, accuracy) = snapshot.hourBuckets()
            .mapNotNull { (hour, facts) -> facts.gatedAccuracyPct(MIN_REVIEWS_PER_BUCKET)?.let { hour to it } }
            .maxByOrNull { it.second } ?: return null
        if (accuracy - overall < MIN_LIFT_POINTS) return null
        return card(
            snapshot,
            title = "Your sharpest study hour",
            body = "You get $accuracy% right at this hour, compared with $overall% overall.",
            action = CoachActionDto(CoachActionKind.ENABLE_REMINDER, label = "Remind me daily", hour = hour),
        )
    }

    companion object {
        const val MIN_LIFT_POINTS = 10
    }
}
