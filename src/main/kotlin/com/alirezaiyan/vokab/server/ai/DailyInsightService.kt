package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.bestEffort
import com.alirezaiyan.vokab.server.notification.NotificationCategory
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.study.UserProgressService

private val logger = KotlinLogging.logger {}

@Service
class DailyInsightService(
    private val dailyInsightRepository: DailyInsightRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val dailyActivityRepository: DailyActivityRepository,
    private val aiService: AiService,
    private val userProgressService: UserProgressService,
    private val pushNotificationService: PushNotificationService,
    private val learnerSignals: LearnerSignals,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val userRepository: UserRepository,
    private val clock: Clock
) {

    data class TodaysInsight(val text: String, val generatedAt: Instant)

    /**
     * The on-demand path (GET /ai/generate-insight, the fallback when the push was missed): today's
     * insight, generated and stored now if there is none. Not transactional, so the AI call holds
     * no connection.
     */
    fun getOrGenerateTodaysInsight(userId: Long): TodaysInsight {
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        val today = LocalDate.now(clock).toString()
        dailyInsightRepository.findByUserAndDate(user, today)
            ?.let { return TodaysInsight(it.insightText, it.generatedAt) }

        logger.info { "Generating new daily insight for userId=$userId" }
        val ctx = AiService.DailyInsightContext(
            stats = userProgressService.calculateProgressStats(userId),
            userName = user.name,
            optimalStudyHour = null,
            accuracyTrend = null,
            topDifficultWord = null,
            primaryLanguage = null,
            sessionCompletionRate = null,
            currentStreak = user.currentStreak
        )
        val insightText = aiService.generateDailyInsight(ctx)
        val saved = saveDailyInsight(user, insightText)
        return TodaysInsight(saved?.insightText ?: insightText, saved?.generatedAt ?: Instant.now(clock))
    }

    /**
     * Generate daily insight for a specific user.
     *
     * Logic (every user, free and premium alike):
     * 1. Return existing insight if already generated today (idempotent).
     * 2. Frequency cap: if the user has an active streak but hasn't reviewed yet,
     *    the 22:00 streak reminder will fire — skip the morning insight to avoid
     *    double-notifying, UNLESS the user's reminder time is ≥ 20:00 (in which
     *    case this insight IS their evening notification).
     * 3. If the user already reviewed today → send a celebration insight.
     *    Otherwise → send a motivational insight.
     */
    fun generateDailyInsightForUser(user: User): DailyInsight? {
        logger.info { "Generating daily insight for user ${user.id}" }

        val today = LocalDate.now(clock).toString()

        val existingInsight = dailyInsightRepository.findByUserAndDate(user, today)
        if (existingInsight != null) {
            logger.debug { "Insight already exists for user ${user.id} on $today" }
            return existingInsight
        }

        val hasActivityToday = dailyActivityRepository.existsByUserAndActivityDate(user, LocalDate.now(clock))
        val streakAtRisk = user.currentStreak > 0 && !hasActivityToday
        val reminderHour = userSettingsRepository.findByUser(user)
            ?.dailyReminderTime?.split(":")?.firstOrNull()?.toIntOrNull() ?: 18

        // Frequency cap: streak reminder fires at 22:00 — don't also send a morning insight
        if (streakAtRisk && reminderHour < 20) {
            logger.debug { "Skipping insight for user ${user.id} — streak reminder will fire tonight" }
            return null
        }

        return try {
            val stats = userProgressService.calculateProgressStats(user.requireId())
            val insightText = if (hasActivityToday) {
                aiService.generateCelebrationInsight(stats, user.name)
            } else {
                val ctx = buildInsightContext(user, stats)
                aiService.generateDailyInsight(ctx)
            }

            val insight = DailyInsight(
                user = user,
                insightText = insightText,
                generatedAt = Instant.now(clock),
                date = today,
                sentViaPush = false
            )
            val saved = try {
                dailyInsightRepository.save(insight)
            } catch (e: DataIntegrityViolationException) {
                // A concurrent request already saved an insight for today — return it
                logger.debug { "Concurrent insight write for user ${user.id} on $today, returning existing row" }
                dailyInsightRepository.findByUserAndDate(user, today) ?: return null
            }
            logger.info { "Generated ${if (hasActivityToday) "celebration" else "motivational"} insight for user ${user.id}" }
            saved
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate daily insight for user ${user.id}" }
            null
        }
    }

    private fun buildInsightContext(user: User, stats: com.alirezaiyan.vokab.server.study.ProgressStatsDto): AiService.DailyInsightContext {
        val optimalStudyHour = bestEffort("Optimal study hour for user=${user.id}") {
            notificationScheduleRepository.findByUser(user)?.optimalSendHour
        }

        val weeklyReport = bestEffort("Weekly report for user=${user.id}") {
            learnerSignals.weeklyReport(user.requireId())
        }
        val accuracyTrend = weeklyReport?.changePercent?.toFloat()

        val topDifficultWord = bestEffort("Difficult word for user=${user.id}") {
            learnerSignals.topDifficultWord(user.requireId())?.wordText
        }

        val primaryLanguage = bestEffort("Primary language for user=${user.id}") {
            learnerSignals.primaryTargetLanguage(user.requireId())
        }

        val sessionCompletionRate = bestEffort("Session completion rate for user=${user.id}") {
            learnerSignals.sessionCompletionRate(user.requireId())?.toFloat()
        }

        return AiService.DailyInsightContext(
            stats = stats,
            userName = user.name,
            optimalStudyHour = optimalStudyHour,
            accuracyTrend = accuracyTrend,
            topDifficultWord = topDifficultWord,
            primaryLanguage = primaryLanguage,
            sessionCompletionRate = sessionCompletionRate,
            currentStreak = user.currentStreak
        )
    }

    /**
     * Send daily insight via push notification.
     */
    fun sendDailyInsightPush(insight: DailyInsight): Boolean {
        logger.info { "Sending daily insight push for user ${insight.user.id}" }

        return try {
            val responses = pushNotificationService.sendNotificationToUser(
                userId = insight.user.requireId(),
                title = "💡 Daily Vocabulary Insight",
                body = insight.insightText,
                data = mapOf(
                    "type" to "daily_insight",
                    "insight_id" to insight.id.toString(),
                    "date" to insight.date
                ),
                category = NotificationCategory.USER
            )

            val success = responses.any { it.success }

            if (success) {
                insight.sentViaPush = true
                insight.pushSentAt = Instant.now(clock)
                dailyInsightRepository.save(insight)
                logger.info { "Successfully sent daily insight push for user ${insight.user.id}" }
            } else {
                logger.warn { "Failed to send daily insight push for user ${insight.user.id}" }
            }

            success
        } catch (e: Exception) {
            logger.error(e) { "Error sending daily insight push for user ${insight.user.id}" }
            false
        }
    }

    /**
     * Generate and push a daily insight for a single user. Used by SmartNotificationDispatcher.
     */
    fun generateAndSendForUser(user: User) {
        val insight = generateDailyInsightForUser(user) ?: return
        if (!insight.sentViaPush) {
            sendDailyInsightPush(insight)
        }
    }

    /**
     * Delivers today's insight as a silent (data-only) push so the in-app insight card stays
     * current for users who get no visible push that day — typically because they already
     * studied. The text rides in the data under "body"; nothing is shown on the device.
     */
    fun refreshInsightSilently(user: User) {
        val insight = generateDailyInsightForUser(user) ?: return
        if (insight.sentViaPush) return
        try {
            val responses = pushNotificationService.sendSilentToUser(
                userId = user.requireId(),
                data = mapOf(
                    "type" to "daily_insight",
                    "body" to insight.insightText,
                    "insight_id" to insight.id.toString(),
                    "date" to insight.date
                )
            )
            if (responses.any { it.success }) {
                insight.sentViaPush = true
                insight.pushSentAt = Instant.now(clock)
                dailyInsightRepository.save(insight)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error sending silent daily insight for user ${user.id}" }
        }
    }

    /**
     * Persist an insight text as today's DailyInsight. Handles concurrent writes by returning
     * the existing row if a unique-constraint violation occurs (race condition safe).
     */
    fun saveDailyInsight(user: User, insightText: String): DailyInsight? {
        val today = LocalDate.now(clock).toString()
        val insight = DailyInsight(
            user = user,
            insightText = insightText,
            generatedAt = Instant.now(clock),
            date = today,
            sentViaPush = false
        )
        return try {
            dailyInsightRepository.save(insight)
        } catch (e: DataIntegrityViolationException) {
            logger.debug { "Concurrent insight write for user ${user.id} on $today, returning existing row" }
            dailyInsightRepository.findByUserAndDate(user, today)
        }
    }

    /**
     * Get latest insight for a user.
     */
    @Transactional(readOnly = true)
    fun getLatestInsightForUser(user: User): DailyInsight? {
        return dailyInsightRepository.findFirstByUserOrderByGeneratedAtDesc(user)
    }
}
