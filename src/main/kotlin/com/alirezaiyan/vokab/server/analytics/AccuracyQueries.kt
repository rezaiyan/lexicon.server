package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Accuracy and speed broken down by level, hour, weekday and language pair. Read-only; backs the analytics dashboards. */
@Service
class AccuracyQueries(
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getAccuracyByLevel(userId: Long): List<AccuracyByLevelResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.getAccuracyByLevel(user).map { p ->
            AccuracyByLevelResponse(
                level = p.level,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getAccuracyByHour(userId: Long): List<HourlyAccuracyResponse> {
        return reviewEventRepository.getAccuracyByHour(userId).map { p ->
            HourlyAccuracyResponse(
                hour = p.hour,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getAccuracyByDayOfWeek(userId: Long): List<DayOfWeekAccuracyResponse> {
        return reviewEventRepository.getAccuracyByDayOfWeek(userId).map { p ->
            DayOfWeekAccuracyResponse(
                dayOfWeek = p.dayOfWeek,
                totalReviews = p.total,
                correctCount = p.correct,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getStatsByLanguagePair(userId: Long): List<LanguagePairStatsResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.getStatsByLanguagePair(user).map { p ->
            LanguagePairStatsResponse(
                sourceLanguage = p.sourceLanguage,
                targetLanguage = p.targetLanguage,
                totalReviews = p.total,
                correctCount = p.correct,
                uniqueWords = p.uniqueWords,
                accuracyPercent = if (p.total > 0) (p.correct.toDouble() / p.total * 100) else 0.0
            )
        }
    }

    @Transactional(readOnly = true)
    fun getResponseTimeTrend(userId: Long): List<ResponseTimeTrendResponse> {
        return reviewEventRepository.getResponseTimeTrend(userId).map { p ->
            ResponseTimeTrendResponse(
                year = p.yr,
                week = p.wk,
                avgResponseTimeMs = p.avgMs
            )
        }
    }
}
