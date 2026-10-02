package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.exception.clientMessage
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.AnalyticsService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(
    private val analyticsService: AnalyticsService
) {

    @PostMapping("/sync")
    fun syncAnalytics(
        @AuthenticationPrincipal user: User,
        @Valid @RequestBody request: SyncAnalyticsRequest
    ): ResponseEntity<ApiResponse<SyncAnalyticsResponse>> {
        return try {
            val response = analyticsService.syncSessions(user, request)
            ResponseEntity.ok(ApiResponse(success = true, data = response))
        } catch (e: Exception) {
            logger.error(e) { "Failed to sync analytics" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to sync")))
        }
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<StudyInsightsResponse>> {
        return try {
            val insights = analyticsService.getStudyInsights(user)
            ResponseEntity.ok(ApiResponse(success = true, data = insights))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get insights" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get insights")))
        }
    }

    @GetMapping("/daily-stats")
    fun getDailyStats(
        @AuthenticationPrincipal user: User,
        @RequestParam start: String,
        @RequestParam end: String
    ): ResponseEntity<ApiResponse<List<DailyStatsResponse>>> {
        return try {
            val stats = analyticsService.getDailyStats(user, start, end)
            ResponseEntity.ok(ApiResponse(success = true, data = stats))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get daily stats" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get daily stats")))
        }
    }

    @GetMapping("/difficult-words")
    fun getDifficultWords(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "3") minReviews: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<DifficultWordResponse>>> {
        return try {
            val words = analyticsService.getDifficultWords(user, minReviews, limit)
            ResponseEntity.ok(ApiResponse(success = true, data = words))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get difficult words" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get difficult words")))
        }
    }

    @GetMapping("/most-reviewed")
    fun getMostReviewedWords(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<MostReviewedWordResponse>>> {
        return try {
            val words = analyticsService.getMostReviewedWords(user, limit)
            ResponseEntity.ok(ApiResponse(success = true, data = words))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get most reviewed words" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get most reviewed words")))
        }
    }

    @GetMapping("/accuracy-by-level")
    fun getAccuracyByLevel(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<AccuracyByLevelResponse>>> {
        return try {
            val data = analyticsService.getAccuracyByLevel(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get accuracy by level" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get accuracy by level")))
        }
    }

    @GetMapping("/accuracy-by-hour")
    fun getAccuracyByHour(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<HourlyAccuracyResponse>>> {
        return try {
            val data = analyticsService.getAccuracyByHour(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get accuracy by hour" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get accuracy by hour")))
        }
    }

    @GetMapping("/accuracy-by-day-of-week")
    fun getAccuracyByDayOfWeek(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<DayOfWeekAccuracyResponse>>> {
        return try {
            val data = analyticsService.getAccuracyByDayOfWeek(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get accuracy by day of week" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get accuracy by day of week")))
        }
    }

    @GetMapping("/sessions")
    fun getRecentSessions(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<StudySessionResponse>>> {
        return try {
            val sessions = analyticsService.getRecentSessions(user, limit)
            ResponseEntity.ok(ApiResponse(success = true, data = sessions))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get recent sessions" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get sessions")))
        }
    }

    @GetMapping("/heatmap")
    fun getHeatmap(
        @AuthenticationPrincipal user: User,
        @RequestParam start: Long,
        @RequestParam end: Long
    ): ResponseEntity<ApiResponse<List<HeatmapDayResponse>>> {
        return try {
            val data = analyticsService.getHeatmap(user, start, end)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get heatmap" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get heatmap")))
        }
    }

    @GetMapping("/level-transitions")
    fun getLevelTransitions(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<LevelTransitionResponse>>> {
        return try {
            val data = analyticsService.getLevelTransitions(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get level transitions" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get level transitions")))
        }
    }

    @GetMapping("/words-mastered")
    fun getWordsMastered(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<MasteredWordResponse>>> {
        return try {
            val data = analyticsService.getWordsMastered(user, limit)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get mastered words" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get mastered words")))
        }
    }

    @GetMapping("/language-stats")
    fun getLanguageStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<LanguagePairStatsResponse>>> {
        return try {
            val data = analyticsService.getStatsByLanguagePair(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get language stats" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get language stats")))
        }
    }

    @GetMapping("/monthly-stats")
    fun getMonthlyStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<MonthlyStatsResponse>>> {
        return try {
            val data = analyticsService.getMonthlyStats(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get monthly stats" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get monthly stats")))
        }
    }

    @GetMapping("/response-time-trend")
    fun getResponseTimeTrend(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<ResponseTimeTrendResponse>>> {
        return try {
            val data = analyticsService.getResponseTimeTrend(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get response time trend" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get response time trend")))
        }
    }

    @GetMapping("/comeback-words")
    fun getComebackWords(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<ComebackWordResponse>>> {
        return try {
            val data = analyticsService.getComebackWords(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get comeback words" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get comeback words")))
        }
    }

    @GetMapping("/weekly-report")
    fun getWeeklyReport(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<WeeklyReportResponse>> {
        return try {
            val data = analyticsService.getWeeklyReport(user)
            ResponseEntity.ok(ApiResponse(success = true, data = data))
        } catch (e: Exception) {
            logger.error(e) { "Failed to get weekly report" }
            ResponseEntity.badRequest()
                .body(ApiResponse(success = false, message = e.clientMessage("Failed to get weekly report")))
        }
    }
}
