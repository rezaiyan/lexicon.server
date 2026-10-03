package com.alirezaiyan.vokab.server.presentation.controller

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.presentation.dto.*
import com.alirezaiyan.vokab.server.service.AnalyticsService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

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
        val response = analyticsService.syncSessions(user, request)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<StudyInsightsResponse>> {
        val insights = analyticsService.getStudyInsights(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = insights))
    }

    @GetMapping("/daily-stats")
    fun getDailyStats(
        @AuthenticationPrincipal user: User,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate
    ): ResponseEntity<ApiResponse<List<DailyStatsResponse>>> {
        val stats = analyticsService.getDailyStats(user, start, end)
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }

    @GetMapping("/difficult-words")
    fun getDifficultWords(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "3") minReviews: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<DifficultWordResponse>>> {
        val words = analyticsService.getDifficultWords(user, minReviews, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = words))
    }

    @GetMapping("/most-reviewed")
    fun getMostReviewedWords(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<MostReviewedWordResponse>>> {
        val words = analyticsService.getMostReviewedWords(user, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = words))
    }

    @GetMapping("/accuracy-by-level")
    fun getAccuracyByLevel(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<AccuracyByLevelResponse>>> {
        val data = analyticsService.getAccuracyByLevel(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/accuracy-by-hour")
    fun getAccuracyByHour(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<HourlyAccuracyResponse>>> {
        val data = analyticsService.getAccuracyByHour(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/accuracy-by-day-of-week")
    fun getAccuracyByDayOfWeek(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<DayOfWeekAccuracyResponse>>> {
        val data = analyticsService.getAccuracyByDayOfWeek(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/sessions")
    fun getRecentSessions(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<StudySessionResponse>>> {
        val sessions = analyticsService.getRecentSessions(user, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = sessions))
    }

    @GetMapping("/heatmap")
    fun getHeatmap(
        @AuthenticationPrincipal user: User,
        @RequestParam start: Long,
        @RequestParam end: Long
    ): ResponseEntity<ApiResponse<List<HeatmapDayResponse>>> {
        val data = analyticsService.getHeatmap(user, start, end)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/level-transitions")
    fun getLevelTransitions(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<LevelTransitionResponse>>> {
        val data = analyticsService.getLevelTransitions(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/words-mastered")
    fun getWordsMastered(
        @AuthenticationPrincipal user: User,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<MasteredWordResponse>>> {
        val data = analyticsService.getWordsMastered(user, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/language-stats")
    fun getLanguageStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<LanguagePairStatsResponse>>> {
        val data = analyticsService.getStatsByLanguagePair(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/monthly-stats")
    fun getMonthlyStats(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<MonthlyStatsResponse>>> {
        val data = analyticsService.getMonthlyStats(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/response-time-trend")
    fun getResponseTimeTrend(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<ResponseTimeTrendResponse>>> {
        val data = analyticsService.getResponseTimeTrend(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/comeback-words")
    fun getComebackWords(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<List<ComebackWordResponse>>> {
        val data = analyticsService.getComebackWords(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/weekly-report")
    fun getWeeklyReport(
        @AuthenticationPrincipal user: User
    ): ResponseEntity<ApiResponse<WeeklyReportResponse>> {
        val data = analyticsService.getWeeklyReport(user)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }
}
