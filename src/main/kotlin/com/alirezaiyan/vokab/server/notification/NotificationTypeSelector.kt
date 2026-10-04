package com.alirezaiyan.vokab.server.notification

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
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.study.MilestoneDetector
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

@Service
class NotificationTypeSelector(
    private val dailyActivityRepository: DailyActivityRepository,
    private val userProgressService: UserProgressService,
    private val learnerSignals: LearnerSignals,
    private val featureAccessService: FeatureAccessService,
    private val milestoneDetector: MilestoneDetector,
    private val clock: Clock
) {
    enum class NotificationType {
        STREAK_RISK, PROGRESS_MILESTONE, WEEKLY_PREVIEW,
        DUE_CARDS, COMEBACK_ALERT, DAILY_INSIGHT, REVIEW_REMINDER,
        MOTIVATION,  // AI-advised re-engagement for COLD/DORMANT users
        NONE
    }

    @Transactional(readOnly = true)
    fun selectType(user: User, schedule: NotificationSchedule): NotificationType {
        // Re-engagement mode: user was suppressed (3+ ignores), use higher-value content
        if (schedule.consecutiveIgnores >= 3) {
            return selectReEngagementType(user)
        }

        val today = LocalDate.now(clock)
        val hasReviewedToday = dailyActivityRepository.existsByUserAndActivityDate(user, today)

        if (hasReviewedToday) {
            return when {
                milestoneDetector.hasPendingMilestone(user) -> NotificationType.PROGRESS_MILESTONE
                featureAccessService.hasActivePremiumAccess(user) -> NotificationType.DAILY_INSIGHT
                else -> NotificationType.NONE
            }
        }

        // Streak risk: only when close to midnight in user's local time
        val localHour = (LocalTime.now(clock).hour + schedule.timezoneOffsetHrs + 24) % 24
        if (user.currentStreak > 0 && localHour >= 20) {
            return NotificationType.STREAK_RISK
        }

        if (milestoneDetector.hasPendingMilestone(user)) {
            return NotificationType.PROGRESS_MILESTONE
        }

        if (LocalDate.now(clock).dayOfWeek == DayOfWeek.MONDAY) {
            runCatching { learnerSignals.weeklyReport(user.requireId()) }
                .getOrNull()
                ?.takeIf { it.sessionsCount > 0 || it.cardsReviewed > 0 }
                ?.let { return NotificationType.WEEKLY_PREVIEW }
        }

        val stats = userProgressService.calculateProgressStats(user.requireId())
        if (stats.dueCards >= 5) return NotificationType.DUE_CARDS

        val comebackWord = runCatching { learnerSignals.topDifficultWord(user.requireId()) }.getOrNull()
        if (comebackWord != null) return NotificationType.COMEBACK_ALERT

        return if (featureAccessService.hasActivePremiumAccess(user)) NotificationType.DAILY_INSIGHT
        else NotificationType.NONE
    }

    /**
     * Re-engagement priority for users who have been suppressed (3+ consecutive ignores).
     * Prefers high-value, actionable, or curiosity-triggering content.
     */
    private fun selectReEngagementType(user: User): NotificationType {
        if (milestoneDetector.hasPendingMilestone(user)) return NotificationType.PROGRESS_MILESTONE

        val stats = userProgressService.calculateProgressStats(user.requireId())
        if (stats.dueCards >= 5) return NotificationType.DUE_CARDS

        val comebackWord = runCatching { learnerSignals.topDifficultWord(user.requireId()) }.getOrNull()
        if (comebackWord != null) return NotificationType.COMEBACK_ALERT

        return if (featureAccessService.hasActivePremiumAccess(user)) NotificationType.DAILY_INSIGHT
        else NotificationType.DUE_CARDS
    }
}
