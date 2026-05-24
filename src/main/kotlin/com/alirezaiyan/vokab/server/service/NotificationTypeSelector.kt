package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.NotificationSchedule
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

@Service
class NotificationTypeSelector(
    private val dailyActivityRepository: DailyActivityRepository,
    private val userProgressService: UserProgressService,
    private val analyticsService: AnalyticsService,
    private val featureAccessService: FeatureAccessService,
    private val milestoneDetector: MilestoneDetector
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

        val today = LocalDate.now(ZoneOffset.UTC)
        val hasReviewedToday = dailyActivityRepository.existsByUserAndActivityDate(user, today)

        if (hasReviewedToday) {
            return when {
                milestoneDetector.hasPendingMilestone(user) -> NotificationType.PROGRESS_MILESTONE
                featureAccessService.hasActivePremiumAccess(user) -> NotificationType.DAILY_INSIGHT
                else -> NotificationType.NONE
            }
        }

        // Streak risk: only when close to midnight in user's local time
        val localHour = (LocalTime.now(ZoneOffset.UTC).hour + schedule.timezoneOffsetHrs + 24) % 24
        if (user.currentStreak > 0 && localHour >= 20) {
            return NotificationType.STREAK_RISK
        }

        if (milestoneDetector.hasPendingMilestone(user)) {
            return NotificationType.PROGRESS_MILESTONE
        }

        if (LocalDate.now(ZoneOffset.UTC).dayOfWeek == DayOfWeek.MONDAY) {
            runCatching { analyticsService.getWeeklyReport(user) }
                .getOrNull()
                ?.takeIf { it.sessionsCount > 0 || it.cardsReviewed > 0 }
                ?.let { return NotificationType.WEEKLY_PREVIEW }
        }

        val stats = userProgressService.calculateProgressStats(user)
        if (stats.dueCards >= 5) return NotificationType.DUE_CARDS

        val comebackWords = runCatching { analyticsService.getDifficultWords(user, minReviews = 3, limit = 1) }
            .getOrNull()
        if (!comebackWords.isNullOrEmpty()) return NotificationType.COMEBACK_ALERT

        return if (featureAccessService.hasActivePremiumAccess(user)) NotificationType.DAILY_INSIGHT
        else NotificationType.NONE
    }

    /**
     * Re-engagement priority for users who have been suppressed (3+ consecutive ignores).
     * Prefers high-value, actionable, or curiosity-triggering content.
     */
    private fun selectReEngagementType(user: User): NotificationType {
        if (milestoneDetector.hasPendingMilestone(user)) return NotificationType.PROGRESS_MILESTONE

        val stats = userProgressService.calculateProgressStats(user)
        if (stats.dueCards >= 5) return NotificationType.DUE_CARDS

        val comebackWords = runCatching { analyticsService.getDifficultWords(user, minReviews = 3, limit = 1) }
            .getOrNull()
        if (!comebackWords.isNullOrEmpty()) return NotificationType.COMEBACK_ALERT

        return if (featureAccessService.hasActivePremiumAccess(user)) NotificationType.DAILY_INSIGHT
        else NotificationType.DUE_CARDS
    }
}
