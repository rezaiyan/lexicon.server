package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.bestEffort
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.ai.DailyInsightService
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.ai.AiService
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

data class NotificationPayload(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val type: NotificationType
)

@Service
class NotificationContentBuilder(
    private val aiService: AiService,
    private val userProgressService: UserProgressService,
    private val learnerSignals: LearnerSignals,
    private val dailyInsightService: DailyInsightService,
    private val milestoneDetector: MilestoneDetector,
    private val clock: Clock,
) {
    fun build(user: User, type: NotificationType, contentHint: String? = null): NotificationPayload {
        return when (type) {
            STREAK_RISK        -> buildStreakRisk(user)
            PROGRESS_MILESTONE -> buildMilestone(user)
            WEEKLY_PREVIEW     -> buildWeeklyPreview(user)
            DUE_CARDS          -> buildDueCards(user)
            COMEBACK_ALERT     -> buildComebackAlert(user)
            DAILY_INSIGHT      -> buildDailyInsight(user)
            REVIEW_REMINDER    -> buildReviewReminder(user)
            MOTIVATION         -> buildMotivation(user, contentHint)
            ADD_WORDS          -> buildAddWords()
            NONE               -> error("Should not build payload for NONE type")
        }
    }

    private fun buildStreakRisk(user: User): NotificationPayload {
        val stats = userProgressService.calculateProgressStats(user.requireId())
        val deadline = streakDeadline()
        val fallback = "Your ${user.currentStreak}-day streak resets $deadline. One short review keeps it alive! 🔥"
        val body = aiCopyOr(fallback) {
            aiService.generateStreakReminderMessage(user.currentStreak, user.name, stats)
        }
        return NotificationPayload(
            title = "Your ${user.currentStreak}-day streak resets $deadline 🔥",
            body = body,
            data = mapOf(
                "type" to "streak_risk",
                "current_streak" to user.currentStreak.toString(),
                "deep_link" to "vokab://review"
            ),
            type = STREAK_RISK
        )
    }

    /**
     * Streak days are UTC days (see StreakService), so the user's local midnight is not the
     * deadline; say how long is left instead.
     */
    private fun streakDeadline(): String {
        val now = Instant.now(clock)
        val nextUtcMidnight = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1)
            .atStartOfDay().toInstant(ZoneOffset.UTC)
        val hoursLeft = Duration.between(now, nextUtcMidnight).toHours()
        return if (hoursLeft < 1) "within the hour" else "in ${hoursLeft}h"
    }

    private fun buildAddWords(): NotificationPayload =
        NotificationPayload(
            title = "Start your word list ✨",
            body = "Add a few words you want to learn, or snap a photo of a text and let Lexicon pick them out.",
            data = mapOf(
                "type" to "add_words",
                "deep_link" to "vokab://words/add"
            ),
            type = ADD_WORDS
        )

    private fun buildDueCards(user: User): NotificationPayload {
        val stats = userProgressService.calculateProgressStats(user.requireId())
        val estimatedMinutes = maxOf(1, (stats.dueCards * 8) / 60)
        val primaryLang = bestEffort("Primary language for user=${user.id}") {
            learnerSignals.primaryTargetLanguage(user.requireId())
        } ?: "vocabulary"
        return NotificationPayload(
            title = "📚 ${stats.dueCards} words are waiting",
            body = "Your $primaryLang review takes ~$estimatedMinutes min.",
            data = mapOf(
                "type" to "due_cards",
                "due_count" to stats.dueCards.toString(),
                "deep_link" to "vokab://review/due"
            ),
            type = DUE_CARDS
        )
    }

    private fun buildComebackAlert(user: User): NotificationPayload {
        val word = learnerSignals.topDifficultWord(user.requireId()) ?: return buildFallbackInsight()
        return NotificationPayload(
            title = "\"${word.wordText}\" wants a rematch 🔄",
            body = "You've missed this one recently. 60 seconds to lock it in.",
            data = mapOf(
                "type" to "comeback_alert",
                "word_id" to word.wordId.toString(),
                "word_text" to word.wordText,
                "deep_link" to "vokab://word/${word.wordId}"
            ),
            type = COMEBACK_ALERT
        )
    }

    private fun buildWeeklyPreview(user: User): NotificationPayload {
        val report = learnerSignals.weeklyReport(user.requireId())
        val changePercent = report.changePercent ?: 0.0
        val trend = when {
            changePercent > 5  -> "▲ ${changePercent.toInt()}% more than last week"
            changePercent < -5 -> "▼ ${(-changePercent).toInt()}% less than last week"
            else -> "steady pace"
        }
        return NotificationPayload(
            title = "Your week in review 📊",
            body = "${report.cardsReviewed} cards · ${report.accuracyPercent.toInt()}% accuracy · $trend",
            data = mapOf(
                "type" to "weekly_preview",
                "deep_link" to "vokab://stats/weekly"
            ),
            type = WEEKLY_PREVIEW
        )
    }

    private fun buildMilestone(user: User): NotificationPayload {
        val milestone = milestoneDetector.getPendingMilestone(user)
            ?: return buildFallbackInsight()
        val body = aiCopyOr("You hit a new milestone: ${milestone.description}! 🏆") {
            aiService.generateMilestoneMessage(milestone, user.name)
        }
        return NotificationPayload(
            title = milestone.title,
            body = body,
            data = mapOf(
                "type" to "milestone",
                "milestone_type" to milestone.type,
                "deep_link" to "vokab://stats/progress"
            ),
            type = PROGRESS_MILESTONE
        )
    }

    private fun buildDailyInsight(user: User): NotificationPayload {
        val insight = dailyInsightService.generateDailyInsightForUser(user)
            ?: return buildFallbackInsight()
        return NotificationPayload(
            title = "💡 Your vocabulary insight",
            body = insight.insightText,
            data = mapOf(
                "type" to "daily_insight",
                "insight_id" to insight.id.toString(),
                "deep_link" to "vokab://insights"
            ),
            type = DAILY_INSIGHT
        )
    }

    private fun buildReviewReminder(user: User): NotificationPayload {
        val stats = userProgressService.calculateProgressStats(user.requireId())
        val dueCards = stats.dueCards
        val body = if (dueCards > 0) {
            val estimatedMinutes = maxOf(1, (dueCards * 8) / 60)
            "You have $dueCards words due for review — takes ~$estimatedMinutes min."
        } else {
            "Time for your daily vocabulary review. Keep the streak going!"
        }
        return NotificationPayload(
            title = "📚 Time to review!",
            body = body,
            data = mapOf(
                "type" to "review_reminder",
                "deep_link" to "vokab://review"
            ),
            type = REVIEW_REMINDER
        )
    }

    /**
     * AI-advised motivational re-engagement notification.
     * contentHint selects the emotional angle; all copy is pre-written (no AI generation).
     */
    private fun buildMotivation(user: User, contentHint: String?): NotificationPayload {
        val stats = bestEffort("Progress stats for user=${user.id}") {
            userProgressService.calculateProgressStats(user.requireId())
        }
        val dueCards = stats?.dueCards ?: 0

        val (title, body) = when (contentHint) {
            "loss_aversion" -> {
                val streak = user.currentStreak
                if (streak > 0) {
                    "Your ${streak}-day streak is at risk 🔥" to
                        "Don't lose the progress you've built. One quick session keeps it alive."
                } else {
                    "Your vocabulary is fading 📉" to
                        "Words you worked hard to learn need a refresh. Come back and lock them in."
                }
            }
            "curiosity" -> {
                if (dueCards > 0) {
                    "Something's waiting for you 👀" to
                        "$dueCards words are piling up. Some you've never gotten right. Ready?"
                } else {
                    "Your vocabulary has a gap 🔍" to
                        "There are words in your list you haven't seen in a while. Curious which ones?"
                }
            }
            "social_proof" ->
                "Others are getting ahead 📈" to
                    "Learners like you are reviewing daily. A few minutes today puts you back in the game."
            "fresh_start" ->
                "Every expert started somewhere 🌱" to
                    "It's never too late to pick it up again. Start fresh — no judgment, just progress."
            "achievement" -> {
                if (dueCards > 0) {
                    "You're closer than you think 🏆" to
                        "Review $dueCards words today and you'll hit your next milestone. That's it."
                } else {
                    "A milestone is within reach 🏆" to
                        "You're just a few reviews away from your next achievement. Don't stop now."
                }
            }
            else ->
                "Time to get back on track 💪" to
                    "Your vocabulary is waiting. A short session today makes all the difference."
        }

        return NotificationPayload(
            title = title,
            body = body,
            data = mapOf(
                "type" to "motivation",
                "deep_link" to "vokab://review"
            ),
            type = MOTIVATION
        )
    }

    private fun buildFallbackInsight(): NotificationPayload {
        return NotificationPayload(
            title = "💡 Keep up the great work!",
            body = "Every word you review brings you closer to fluency.",
            data = mapOf("type" to "daily_insight", "deep_link" to "vokab://insights"),
            type = DAILY_INSIGHT
        )
    }

    /** AI-written copy, or [fallback] when the AI is unavailable: the notification still goes out. */
    private inline fun aiCopyOr(fallback: String, generate: () -> String): String =
        try {
            generate()
        } catch (e: UpstreamServiceException) {
            logger.warn { "AI notification copy unavailable, using fallback: ${e.message}" }
            fallback
        }
}
