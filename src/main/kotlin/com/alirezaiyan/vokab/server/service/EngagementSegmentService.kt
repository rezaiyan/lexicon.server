package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.NotificationLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
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
 */
@Service
class EngagementSegmentService(
    private val notificationLogRepository: NotificationLogRepository
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
        if (openedInLast3 == 1) return EngagementSegment.WARM

        // 0 of last 3 opened — check how long since any open
        val lastOpenLog = notificationLogRepository
            .findTopByUserIdAndOpenedAtIsNotNullOrderBySentAtDesc(userId)

        val daysSinceLastOpen = lastOpenLog?.openedAt?.let { openedAt ->
            ChronoUnit.DAYS.between(openedAt, Instant.now())
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
}
