package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
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
     * 4. If sparse data: fall back to UserSettings.dailyReminderTime, a local time, converted
     *    to UTC with the device timezone when the client reported one.
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
        val localHour = settings?.dailyReminderTime
            ?.split(":")
            ?.firstOrNull()
            ?.toIntOrNull()
            ?: return 18
        val offset = utcOffsetHours(settings.timezone) ?: return localHour
        return Math.floorMod(localHour - offset, 24)
    }

    /**
     * Whole-hour UTC offset of an IANA zone right now (DST included), or null when the zone is
     * missing or unknown. Half-hour zones round down (+05:30 → 5).
     */
    fun utcOffsetHours(timezone: String?): Int? {
        if (timezone.isNullOrBlank()) return null
        val zone = try {
            ZoneId.of(timezone)
        } catch (e: DateTimeException) {
            logger.debug { "Ignoring unknown timezone '$timezone': ${e.message}" }
            return null
        }
        val offsetSeconds = zone.rules.getOffset(Instant.now(clock)).totalSeconds
        return Math.floorDiv(offsetSeconds, 3600)
    }

    /**
     * The client reported a (new) device timezone: use its offset from now on, and have the
     * nightly refresh recompute the send hour instead of waiting out its weekly cache.
     */
    @Transactional
    fun applyTimezone(userId: Long, timezone: String) {
        val offset = utcOffsetHours(timezone) ?: return
        val schedule = notificationScheduleRepository.findByUserId(userId) ?: return
        schedule.timezoneOffsetHrs = offset
        schedule.lastComputedAt = null
        schedule.updatedAt = Instant.now(clock)
        notificationScheduleRepository.save(schedule)
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
                val reportedOffset = utcOffsetHours(userSettingsRepository.findByUserId(user.requireId())?.timezone)
                if (existing?.lastComputedAt?.isAfter(sevenDaysAgo) == true) {
                    // Send hour is still fresh, but follow DST shifts of a reported zone nightly
                    if (reportedOffset != null && reportedOffset != existing.timezoneOffsetHrs) {
                        existing.timezoneOffsetHrs = reportedOffset
                        existing.updatedAt = Instant.now(clock)
                        notificationScheduleRepository.save(existing)
                    }
                    return@runCatching
                }

                val (hour, confidence) = computeOptimalHour(user.requireId())
                val offset = reportedOffset ?: deriveTimezoneOffset(user.requireId())

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
