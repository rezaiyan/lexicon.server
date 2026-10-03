package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import com.alirezaiyan.vokab.server.presentation.dto.DayActivity
import com.alirezaiyan.vokab.server.presentation.dto.LanguagePair
import com.alirezaiyan.vokab.server.presentation.dto.ProfileStatsResponse
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.alirezaiyan.vokab.server.domain.entity.requireId

@Service
class ProfileStatsService(
    private val dailyActivityRepository: DailyActivityRepository,
    private val wordRepository: WordRepository,
    private val streakService: StreakService,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getProfileStats(userId: Long): ProfileStatsResponse {
        val user = userRepository.getReferenceById(userId)
        val streakInfo = streakService.getUserStreak(user.requireId())
        val longestStreak = calculateLongestStreak(user)

        return ProfileStatsResponse(
            currentStreak = streakInfo.currentStreak,
            longestStreak = maxOf(longestStreak, streakInfo.currentStreak, user.longestStreak),
            memberSince = user.createdAt.atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE),
            weeklyActivity = getWeeklyActivity(user),
            languages = getLanguagePairs(user)
        )
    }

    private fun calculateLongestStreak(user: User): Int {
        val activities = dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user)
        if (activities.isEmpty()) return 0

        val dates = activities.map { it.activityDate }.sorted()

        var longest = 1
        var current = 1

        for (i in 1 until dates.size) {
            if (dates[i] == dates[i - 1].plusDays(1)) {
                current++
                if (current > longest) longest = current
            } else if (dates[i] != dates[i - 1]) {
                current = 1
            }
        }

        return longest
    }

    private fun getWeeklyActivity(user: User): List<DayActivity> {
        val today = LocalDate.now(clock)
        val startDate = today.minusDays(6)
        val activities = dailyActivityRepository.findRecentActivities(user, startDate)

        val activityMap = activities.associate { it.activityDate to it.reviewCount }

        return (0L..6L).map { daysAgo ->
            val date = startDate.plusDays(daysAgo)
            DayActivity(
                date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                reviewCount = activityMap[date] ?: 0
            )
        }
    }

    private fun getLanguagePairs(user: User): List<LanguagePair> {
        return wordRepository.findLanguagePairsWithCount(user).map { row ->
            LanguagePair(
                sourceLanguage = row[0] as String,
                targetLanguage = row[1] as String,
                wordCount = (row[2] as Long).toInt()
            )
        }
    }
}
