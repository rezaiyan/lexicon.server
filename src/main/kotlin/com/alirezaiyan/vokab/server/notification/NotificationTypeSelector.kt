package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.shared.bestEffort
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

@Service
class NotificationTypeSelector(
    private val dailyActivityRepository: DailyActivityRepository,
    private val userProgressService: UserProgressService,
    private val learnerSignals: LearnerSignals,
    private val milestoneDetector: MilestoneDetector,
    private val practiceNudgePolicy: PracticeNudgePolicy,
    private val clock: Clock
) {
    enum class NotificationType {
        STREAK_RISK, PROGRESS_MILESTONE, WEEKLY_PREVIEW,
        DUE_CARDS, COMEBACK_ALERT, DAILY_INSIGHT, REVIEW_REMINDER,
        MOTIVATION,  // AI-advised re-engagement for COLD/DORMANT users
        ADD_WORDS,   // Activation: the user has no words yet, so there is nothing to review
        WORD_RUSH,   // Practice nudges when nothing is due; see PracticeNudgePolicy
        LISTENING,
        NONE
    }

    /**
     * Picks the one push worth sending today, or [NotificationType.NONE].
     *
     * A user who already studied today only hears about something new to them (a milestone,
     * the Monday recap); reminding them to do what they just did is noise. With little to review,
     * a Word Rush or Listening nudge may take the slot (PracticeNudgePolicy decides). Generic
     * content (insight, add-words nudge) never goes out twice in a row.
     */
    @Transactional(readOnly = true)
    fun selectType(user: User, schedule: NotificationSchedule): NotificationType {
        if (studiedToday(user)) {
            return selectForActiveToday(user)
        }

        // Re-engagement mode: user was suppressed (3+ ignores), use higher-value content
        if (schedule.consecutiveIgnores >= 3) {
            return selectReEngagementType(user, schedule).notRepeating(schedule)
        }

        // Streak risk: only when close to the end of the user's day
        val localHour = (LocalTime.now(clock).hour + schedule.timezoneOffsetHrs + 24) % 24
        if (user.currentStreak > 0 && localHour >= 20) {
            return NotificationType.STREAK_RISK
        }

        if (milestoneDetector.hasPendingMilestone(user)) {
            return NotificationType.PROGRESS_MILESTONE
        }

        weeklyRecapIfDue(user)?.let { return it }

        val stats = userProgressService.calculateProgressStats(user.requireId())
        if (stats.totalWords == 0) return NotificationType.ADD_WORDS.notRepeating(schedule)
        if (stats.dueCards >= MIN_DUE_CARDS) return NotificationType.DUE_CARDS

        if (hasComebackWord(user)) return NotificationType.COMEBACK_ALERT

        practiceNudgePolicy.pick(user, schedule, stats.totalWords)?.let { return it }
        return NotificationType.DAILY_INSIGHT.notRepeating(schedule)
    }

    /** Whether the user has studied in the current streak day (a UTC day, see StreakService). */
    @Transactional(readOnly = true)
    fun studiedToday(user: User): Boolean =
        dailyActivityRepository.existsByUserAndActivityDate(user, LocalDate.now(clock))

    private fun selectForActiveToday(user: User): NotificationType = when {
        milestoneDetector.hasPendingMilestone(user) -> NotificationType.PROGRESS_MILESTONE
        else -> weeklyRecapIfDue(user) ?: NotificationType.NONE
    }

    /** Monday recap of last week, only when there was something to recap. */
    private fun weeklyRecapIfDue(user: User): NotificationType? {
        if (LocalDate.now(clock).dayOfWeek != DayOfWeek.MONDAY) return null
        return bestEffort("Weekly report for user=${user.id}") { learnerSignals.weeklyReport(user.requireId()) }
            ?.takeIf { it.sessionsCount > 0 || it.cardsReviewed > 0 }
            ?.let { NotificationType.WEEKLY_PREVIEW }
    }

    /**
     * Re-engagement priority for users who have been suppressed (3+ consecutive ignores).
     * Prefers high-value, actionable, or curiosity-triggering content.
     */
    private fun selectReEngagementType(user: User, schedule: NotificationSchedule): NotificationType {
        if (milestoneDetector.hasPendingMilestone(user)) return NotificationType.PROGRESS_MILESTONE

        val stats = userProgressService.calculateProgressStats(user.requireId())
        return when {
            stats.totalWords == 0 -> NotificationType.ADD_WORDS
            stats.dueCards >= MIN_DUE_CARDS -> NotificationType.DUE_CARDS
            hasComebackWord(user) -> NotificationType.COMEBACK_ALERT
            else -> practiceNudgePolicy.pick(user, schedule, stats.totalWords) ?: NotificationType.DAILY_INSIGHT
        }
    }

    private fun hasComebackWord(user: User): Boolean =
        bestEffort("Difficult word for user=${user.id}") { learnerSignals.topDifficultWord(user.requireId()) } != null

    /** Low-urgency content skips a day rather than repeating the previous push. */
    private fun NotificationType.notRepeating(schedule: NotificationSchedule): NotificationType =
        if (this in LOW_URGENCY_TYPES && schedule.lastSentType == name) NotificationType.NONE else this

    companion object {
        private const val MIN_DUE_CARDS = 5
        private val LOW_URGENCY_TYPES = setOf(NotificationType.DAILY_INSIGHT, NotificationType.ADD_WORDS)
    }
}
