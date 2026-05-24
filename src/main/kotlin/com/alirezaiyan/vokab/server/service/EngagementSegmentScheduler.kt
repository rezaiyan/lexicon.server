package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.service.EngagementSegmentService.EngagementSegment
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Nightly batch that:
 * 1. Recomputes each user's engagement segment from notification history.
 * 2. For COLD/DORMANT users with a stale or missing AI decision (>7 days old),
 *    calls NotificationAiAdvisor to get a fresh strategy and caches the result.
 *
 * Invoked by ScheduledTasks — not a scheduler itself.
 */
@Service
class EngagementSegmentScheduler(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val engagementSegmentService: EngagementSegmentService,
    private val notificationEngagementService: NotificationEngagementService,
    private val notificationAiAdvisor: NotificationAiAdvisor,
    private val userProgressService: UserProgressService
) {
    private val aiCacheDays = 7L

    fun refreshAll(users: List<User>) {
        var segmentUpdated = 0
        var aiRefreshed = 0
        var aiSkipped = 0

        for (user in users) {
            runCatching {
                val userId = user.id ?: return@runCatching
                val segment = engagementSegmentService.computeSegment(userId)
                saveSegment(userId, segment)
                segmentUpdated++

                val isColdOrDormant = segment == EngagementSegment.COLD ||
                    segment == EngagementSegment.DORMANT

                if (isColdOrDormant && needsAiRefresh(userId)) {
                    val context = buildContext(user, segment)
                    // AI call — intentionally outside any transaction
                    val advice = notificationAiAdvisor.advise(context)
                    saveAiAdvice(userId, advice)
                    aiRefreshed++
                } else if (isColdOrDormant) {
                    aiSkipped++
                }
            }.onFailure { e ->
                logger.warn(e) { "Segment refresh failed for user=${user.id}" }
            }
        }

        logger.info {
            "Engagement segment refresh complete — users=${users.size}, " +
                "segmentUpdated=$segmentUpdated, aiRefreshed=$aiRefreshed, aiSkipped=$aiSkipped"
        }
    }

    @Transactional
    fun saveSegment(userId: Long, segment: EngagementSegment) {
        notificationScheduleRepository.findByUserId(userId)?.let { schedule ->
            schedule.engagementSegment = segment.name
            schedule.updatedAt = Instant.now()
            notificationScheduleRepository.save(schedule)
        }
    }

    @Transactional(readOnly = true)
    fun needsAiRefresh(userId: Long): Boolean {
        val schedule = notificationScheduleRepository.findByUserId(userId) ?: return false
        val cutoff = Instant.now().minus(aiCacheDays, ChronoUnit.DAYS)
        return schedule.aiDecidedAt == null || schedule.aiDecidedAt!!.isBefore(cutoff)
    }

    @Transactional(readOnly = true)
    fun buildContext(user: User, segment: EngagementSegment): NotificationAiAdvisor.UserNotificationContext {
        val userId = user.id!!
        val stats7d  = notificationEngagementService.getEngagementStats(userId, windowDays = 7)
        val stats30d = notificationEngagementService.getEngagementStats(userId, windowDays = 30)
        val daysSinceOpen = notificationEngagementService.getDaysSinceLastOpen(userId)
        val dueCards = runCatching { userProgressService.calculateProgressStats(user).dueCards }.getOrElse { 0 }
        val accountAgeDays = user.createdAt?.let { ChronoUnit.DAYS.between(it, Instant.now()) } ?: 0L

        return NotificationAiAdvisor.UserNotificationContext(
            userId           = userId,
            segment          = segment.name,
            openRate7dPercent  = stats7d.openRatePercent,
            openRate30dPercent = stats30d.openRatePercent,
            daysSinceLastOpen  = daysSinceOpen,
            currentStreak      = user.currentStreak,
            longestStreak      = user.longestStreak,
            dueCards           = dueCards,
            accountAgeDays     = accountAgeDays
        )
    }

    @Transactional
    fun saveAiAdvice(userId: Long, advice: NotificationAiAdvisor.AiAdvice) {
        notificationScheduleRepository.findByUserId(userId)?.let { schedule ->
            schedule.aiAction       = advice.action
            schedule.aiIntervalDays = advice.intervalDays
            schedule.aiContentHint  = advice.contentHint
            schedule.aiDecidedAt    = Instant.now()
            schedule.updatedAt      = Instant.now()
            notificationScheduleRepository.save(schedule)
            logger.debug {
                "AI advice saved for user=$userId: action=${advice.action}, " +
                    "interval=${advice.intervalDays}d, hint=${advice.contentHint}"
            }
        }
    }
}
