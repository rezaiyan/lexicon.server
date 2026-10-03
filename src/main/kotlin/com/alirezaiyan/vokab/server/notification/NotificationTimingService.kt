package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import com.alirezaiyan.vokab.server.user.requireId

private val logger = KotlinLogging.logger {}

@Service
class NotificationTimingService(
    private val reviewEventRepository: ReviewEventRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val clock: Clock
) {
    companion object {
        private const val LOOKBACK_DAYS = 90L
        private const val MIN_REVIEWS_FOR_CONFIDENCE = 10
        private const val HIGH_CONFIDENCE_THRESHOLD = 30
    }

    /**
     * Computes the UTC hour with the highest review activity for a user.
     *
     * Algorithm:
     * 1. Load all ReviewEvent.reviewedAt timestamps from the past 90 days.
     * 2. Group by UTC hour → frequency histogram [0..23].
     * 3. If enough data: return the peak hour.
     * 4. If sparse data: fall back to UserSettings.dailyReminderTime.
     * 5. Final fallback: 18 (6 PM UTC).
     *
     * Returns: Pair(hour: Int, confidence: Int 0–100)
     */
    @Transactional(readOnly = true)
    fun computeOptimalHour(userId: Long): Pair<Int, Int> {
        val since = Instant.now(clock).minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        val timestamps = reviewEventRepository.findReviewedAtByUserIdSince(userId, since.toEpochMilli())

        if (timestamps.size < MIN_REVIEWS_FOR_CONFIDENCE) {
            val fallbackHour = getFallbackHour(userId)
            return Pair(fallbackHour, 0)
        }

        val histogram = IntArray(24)
        for (epochMs in timestamps) {
            val hour = Instant.ofEpochMilli(epochMs)
                .atZone(ZoneOffset.UTC)
                .hour
            histogram[hour]++
        }

        val peakHour = histogram.indices.maxByOrNull { histogram[it] } ?: 18
        val peakCount = histogram[peakHour]
        val confidence = minOf(100, (peakCount * 100) / HIGH_CONFIDENCE_THRESHOLD)

        return Pair(peakHour, confidence)
    }

    /**
     * Derives approximate UTC offset by comparing review timestamps to expected
     * "waking hours" (6 AM – 11 PM local). Finds the offset that maximises
     * the fraction of reviews falling in that local window.
     *
     * Returns integer UTC offset in hours (-12..+14).
     */
    @Transactional(readOnly = true)
    fun deriveTimezoneOffset(userId: Long): Int {
        val since = Instant.now(clock).minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        val timestamps = reviewEventRepository.findReviewedAtByUserIdSince(userId, since.toEpochMilli())
        if (timestamps.size < MIN_REVIEWS_FOR_CONFIDENCE) return 0

        var bestOffset = 0
        var bestScore = 0

        for (offsetHrs in -12..14) {
            val score = timestamps.count { epochMs ->
                val localHour = (Instant.ofEpochMilli(epochMs)
                    .atZone(ZoneOffset.UTC).hour + offsetHrs + 24) % 24
                localHour in 6..23
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = offsetHrs
            }
        }
        return bestOffset
    }

    private fun getFallbackHour(userId: Long): Int {
        val settings = userSettingsRepository.findByUserId(userId)
        return settings?.dailyReminderTime
            ?.split(":")
            ?.firstOrNull()
            ?.toIntOrNull()
            ?: 18
    }

    /**
     * Nightly job: recompute optimal hour for all active users with push tokens.
     * Skips users computed in the last 7 days (stable enough).
     * Each user's schedule save runs in its own independent transaction.
     */
    fun refreshSchedulesForAllUsers(users: List<User>) {
        val sevenDaysAgo = Instant.now(clock).minus(7, ChronoUnit.DAYS)
        for (user in users) {
            runCatching {
                val existing = notificationScheduleRepository.findByUser(user)
                if (existing?.lastComputedAt?.isAfter(sevenDaysAgo) == true) return@runCatching

                val (hour, confidence) = computeOptimalHour(user.requireId())
                val offset = deriveTimezoneOffset(user.requireId())

                val schedule = existing ?: NotificationSchedule(user = user)
                schedule.optimalSendHour = hour
                schedule.timezoneOffsetHrs = offset
                schedule.dataConfidence = confidence
                schedule.lastComputedAt = Instant.now(clock)
                schedule.updatedAt = Instant.now(clock)

                notificationScheduleRepository.save(schedule)
            }.onFailure { logger.warn(it) { "Failed to compute timing for user ${user.id}" } }
        }
    }
}
