package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.bestEffort
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
     * 2. If the user already reviewed today → a celebration insight.
     *    Otherwise → a motivational insight.
     *
     * Which push goes out, and when, is NotificationTypeSelector's call; this only writes it.
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
     * Delivers today's insight as a silent (data-only) push so the in-app insight card stays
     * current for users who get no visible push that day — typically because they already
     * studied. The text rides in the data under "body"; nothing is shown on the device.
     *
     * Only for app versions that handle it: older iOS builds display a data-only push carrying a
     * "body" as a notification. The app version that handles it is also the first to report the
     * device timezone, so a stored timezone marks a capable client.
     */
    fun refreshInsightSilently(user: User) {
        if (userSettingsRepository.findByUser(user)?.timezone == null) return
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
