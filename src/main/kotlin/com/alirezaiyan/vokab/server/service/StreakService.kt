package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.DailyActivity
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.DailyActivityRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Service
class StreakService(
    private val dailyActivityRepository: DailyActivityRepository,
    private val userRepository: UserRepository,
    private val clock: Clock
) {
    
    /**
     * Record user activity and update streak
     * Called when user opens review section
     */
    @Transactional
    fun recordActivity(userId: Long, count: Int = 1): User {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        val today = LocalDate.now(clock)

        // Check if activity already recorded today
        val existingActivity = dailyActivityRepository.findByUserAndActivityDate(user, today)

        if (existingActivity.isPresent) {
            // Already recorded today, increment review count by the number of words reviewed
            val activity = existingActivity.get()
            activity.reviewCount += count
            dailyActivityRepository.save(activity)
            logger.debug { "Updated review count for userId=${user.id} on $today, count=${activity.reviewCount}" }

            // Return user as-is (streak already updated when first activity was recorded today)
            return user
        } else {
            // Record new activity for today
            val newActivity = DailyActivity(
                user = user,
                activityDate = today,
                reviewCount = count
            )
            dailyActivityRepository.save(newActivity)
            logger.info { "✅ Recorded new activity for userId=${user.id} on $today, count=$count" }

            // Update streak based on last activity
            val updatedUser = updateStreakOnNewActivity(user, today)
            return updatedUser
        }
    }
    
    /**
     * Calculate current streak from DailyActivity records
     * Counts consecutive days starting from today going backwards
     * Returns 0 if there's any gap in consecutive days
     */
    private fun calculateCurrentStreak(user: User, today: LocalDate): Int {
        val allActivities = dailyActivityRepository.findAllByUserOrderByActivityDateDesc(user)
        
        if (allActivities.isEmpty()) {
            return 0
        }
        
        // Create a set of activity dates for fast lookup
        val activityDates = allActivities.map { it.activityDate }.toSet()
        
        // Check if there's activity today
        if (today !in activityDates) {
            return 0
        }
        
        // Count consecutive days starting from today going backwards
        var currentStreak = 0
        var checkDate = today
        
        // Continue checking backwards as long as consecutive days have activities
        while (checkDate in activityDates) {
            currentStreak++
            checkDate = checkDate.minusDays(1)
        }
        
        return currentStreak
    }
    
    /**
     * Update streak when new activity is recorded
     * Recalculates streak from consecutive days of activities
     */
    private fun updateStreakOnNewActivity(user: User, today: LocalDate): User {
        val newCurrentStreak = calculateCurrentStreak(user, today)
        
        logger.info { "📈 Streak calculated: $newCurrentStreak consecutive days (last activity: $today)" }

        // Update user including longest streak
        val newLongestStreak = maxOf(user.longestStreak, newCurrentStreak)
        user.currentStreak = newCurrentStreak
        user.longestStreak = newLongestStreak

        val saved = userRepository.save(user)
        logger.info { "✅ Streak updated for userId=${user.id}: current=$newCurrentStreak, longest=$newLongestStreak" }
        
        return saved
    }
    
    /**
     * Get user's current streak and longest streak
     * Recalculates streak from DailyActivity records (not stored value)
     * Updates user record if streak changed
     */
    @Transactional
    fun getUserStreak(userId: Long): StreakInfo {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        
        val today = LocalDate.now(clock)
        
        // Recalculate streak from activities
        val calculatedStreak = calculateCurrentStreak(user, today)

        // Update user if streak changed
        if (calculatedStreak != user.currentStreak) {
            user.currentStreak = calculatedStreak
            userRepository.save(user)
            logger.debug { "Updated streak for userId=${user.id}: current=$calculatedStreak" }
        }

        return StreakInfo(
            currentStreak = calculatedStreak
        )
    }
}

data class StreakInfo(
    val currentStreak: Int
)


