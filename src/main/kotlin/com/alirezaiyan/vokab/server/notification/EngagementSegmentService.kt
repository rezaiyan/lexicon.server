package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Computes a user's engagement segment from notification_log history.
 *
 * Segments (evaluated in priority order):
 *  HOT      — opened ≥2 of last 3 sent
 *  WARM     — opened 1 of last 3 sent
 *  COOLING  — opened 0 of last 3, but last open was <7 days ago
 *  COLD     — last open 7–30 days ago (or never opened, <10 total sent)
 *  DORMANT  — last open >30 days ago (or never opened, ≥10 total sent)
 *
 * Opens alone undercount engagement: a learner who studied in the last
 * [RECENT_STUDY_DAYS] days is at least WARM, so they never get the cold-user
 * treatment (AI pause, "your vocabulary is fading" copy) while using the app.
 */
@Service
class EngagementSegmentService(
    private val notificationLogRepository: NotificationLogRepository,
    private val dailyActivityRepository: DailyActivityRepository,
    private val clock: Clock
) {
    enum class EngagementSegment { HOT, WARM, COOLING, COLD, DORMANT }

    @Transactional(readOnly = true)
    fun computeSegment(userId: Long): EngagementSegment {
        val last3 = notificationLogRepository.findTop3ByUserIdOrderBySentAtDesc(userId)

        if (last3.isEmpty()) {
            logger.debug { "No notification history for user=$userId → WARM" }
            return EngagementSegment.WARM
        }

        val openedInLast3 = last3.count { it.openedAt != null }

        if (openedInLast3 >= 2) return EngagementSegment.HOT
        if (openedInLast3 == 1 || studiedRecently(userId)) return EngagementSegment.WARM

        // 0 of last 3 opened — check how long since any open
        val lastOpenLog = notificationLogRepository
            .findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId)

        val daysSinceLastOpen = lastOpenLog?.openedAt?.let { openedAt ->
            ChronoUnit.DAYS.between(openedAt, Instant.now(clock))
        }

        return when {
            daysSinceLastOpen == null -> {
                // Never opened — base on volume
                if (last3.size < 3) EngagementSegment.COLD else EngagementSegment.DORMANT
            }
            daysSinceLastOpen < 7   -> EngagementSegment.COOLING
            daysSinceLastOpen <= 30 -> EngagementSegment.COLD
            else                    -> EngagementSegment.DORMANT
        }.also { segment ->
            logger.debug { "Segment for user=$userId: $segment (daysSinceOpen=$daysSinceLastOpen, openedInLast3=$openedInLast3)" }
        }
    }

    private fun studiedRecently(userId: Long): Boolean {
        val since = LocalDate.now(clock).minusDays(RECENT_STUDY_DAYS)
        return dailyActivityRepository.existsByUserIdAndActivityDateGreaterThanEqual(userId, since)
    }

    private companion object {
        const val RECENT_STUDY_DAYS = 3L
    }
}
