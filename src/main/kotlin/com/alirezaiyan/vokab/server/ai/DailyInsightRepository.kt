package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DailyInsightRepository : JpaRepository<DailyInsight, Long> {
    
    fun findByUserAndDate(user: User, date: String): DailyInsight?
    
    fun findByUserAndDateOrderByGeneratedAtDesc(user: User, date: String): DailyInsight?
    
    @Query("SELECT d FROM DailyInsight d WHERE d.user = :user AND d.date = :date AND d.sentViaPush = true")
    fun findSentInsightByUserAndDate(user: User, date: String): DailyInsight?
    
    fun findFirstByUserOrderByGeneratedAtDesc(user: User): DailyInsight?

    @Query("SELECT COUNT(d) FROM DailyInsight d WHERE d.user = :user AND d.date = :date")
    fun countInsightsByUserAndDate(user: User, date: String): Long
    
    @Query("SELECT DISTINCT d.user FROM DailyInsight d WHERE d.date = :date")
    fun findUsersWithInsightsForDate(date: String): List<User>
    
    fun findByUser(user: User): List<DailyInsight>

    fun existsByUserAndDateAndSentViaPushTrue(user: User, date: String): Boolean
}
