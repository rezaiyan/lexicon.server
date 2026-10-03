package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.presentation.dto.ProgressStatsDto
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class MilestoneDetector(
    private val userProgressService: UserProgressService,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val objectMapper: ObjectMapper
) {
    data class MilestoneEvent(
        val type: String,
        val title: String,
        val description: String,
        val value: Long
    )

    private val wordMilestones = listOf(25L, 50, 100, 250, 500, 1000, 2500, 5000)
    private val masteredMilestones = listOf(1L, 5, 10, 25, 50, 100, 500)

    @Transactional(readOnly = true)
    fun hasPendingMilestone(user: User): Boolean = getPendingMilestone(user) != null

    @Transactional(readOnly = true)
    fun getPendingMilestone(user: User): MilestoneEvent? {
        val stats = userProgressService.calculateProgressStats(user.requireId())
        val schedule = notificationScheduleRepository.findByUser(user) ?: return null
        val lastSnapshot = schedule.lastMilestoneSnapshot?.let { parseSnapshot(it) } ?: emptyMap()

        for (threshold in wordMilestones) {
            val prevTotal = lastSnapshot["total_words"] ?: 0L
            if (prevTotal < threshold && stats.totalWords >= threshold) {
                return MilestoneEvent(
                    type = "words_added",
                    title = "Vocabulary milestone!",
                    description = "a vocabulary collection milestone",
                    value = threshold
                )
            }
        }

        for (threshold in masteredMilestones) {
            val prevMastered = lastSnapshot["mastered_words"] ?: 0L
            if (prevMastered < threshold && stats.level6Count >= threshold) {
                return MilestoneEvent(
                    type = "words_mastered",
                    title = "Mastery milestone!",
                    description = "a mastery milestone",
                    value = threshold
                )
            }
        }

        if (user.currentStreak > 0 && user.currentStreak >= user.longestStreak &&
            (lastSnapshot["longest_streak"] ?: 0L) < user.longestStreak) {
            return MilestoneEvent(
                type = "streak_record",
                title = "🏆 New streak record!",
                description = "${user.currentStreak}-day personal best",
                value = user.currentStreak.toLong()
            )
        }

        return null
    }

    @Transactional
    fun recordMilestoneSnapshot(user: User, stats: ProgressStatsDto) {
        val schedule = notificationScheduleRepository.findByUser(user) ?: return
        schedule.lastMilestoneSnapshot = objectMapper.writeValueAsString(
            mapOf(
                "total_words" to stats.totalWords,
                "mastered_words" to stats.level6Count,
                "longest_streak" to user.longestStreak
            )
        )
        notificationScheduleRepository.save(schedule)
        logger.debug { "Recorded milestone snapshot for user=${user.id}" }
    }

    private fun parseSnapshot(json: String): Map<String, Long> =
        runCatching {
            objectMapper.readValue(json, object : TypeReference<Map<String, Long>>() {})
        }.getOrElse {
            logger.warn(it) { "Failed to parse milestone snapshot JSON" }
            emptyMap()
        }
}
