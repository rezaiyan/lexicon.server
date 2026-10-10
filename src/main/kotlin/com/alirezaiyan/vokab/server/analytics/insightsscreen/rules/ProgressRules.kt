package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.*
import org.springframework.stereotype.Component

@Component
class MilestoneNearRule : CoachRule {
    override val type = "MILESTONE_NEAR"
    override val priority = 70

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        if (snapshot.masteredTotal < MIN_MASTERED) return null
        val next = nextMilestone(snapshot.masteredTotal)
        val remaining = (next - snapshot.masteredTotal).toInt()
        if (remaining > MAX_REMAINING) return null
        return card(
            snapshot,
            title = "${plural(remaining, "word")} from $next mastered",
            body = "Words that reach the last stage count as mastered. A focused session could get you there.",
            action = CoachActionDto(CoachActionKind.START_REVIEW, label = "Keep going"),
        )
    }

    companion object {
        const val MIN_MASTERED = 1L
        const val MAX_REMAINING = 10
    }
}

/** Fallback card: always eligible, lowest priority. */
@Component
class WeekTrendRule : CoachRule {
    override val type = "WEEK_TREND"
    override val priority = 10

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto {
        val thisWeek = snapshot.reviewsIn(snapshot.thisWeek()).size
        val lastWeek = snapshot.reviewsIn(snapshot.lastWeek()).size
        val action = CoachActionDto(CoachActionKind.START_REVIEW, label = "Review now")
        return when {
            thisWeek == 0 -> card(snapshot, "Start your week", "Your first review this week gets the ball rolling.", action)
            thisWeek >= lastWeek -> card(
                snapshot,
                title = "${plural(thisWeek, "review")} this week",
                body = if (lastWeek == 0) "Great start. Keep it up." else "That's ${thisWeek - lastWeek} more than last week. Nice momentum.",
                action = action,
            )
            else -> card(
                snapshot,
                title = "${plural(thisWeek, "review")} this week",
                body = "${lastWeek - thisWeek} more to match last week",
                action = action,
            )
        }
    }
}
