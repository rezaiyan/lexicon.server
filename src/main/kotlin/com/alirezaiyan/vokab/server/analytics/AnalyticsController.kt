package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
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
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SyncAnalyticsRequest
    ): ResponseEntity<ApiResponse<SyncAnalyticsResponse>> {
        val response = analyticsService.syncSessions(user.id, request)
        return ResponseEntity.ok(ApiResponse(success = true, data = response))
    }

    @GetMapping("/insights")
    fun getInsights(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<StudyInsightsResponse>> {
        val insights = analyticsService.getStudyInsights(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = insights))
    }

    @GetMapping("/daily-stats")
    fun getDailyStats(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate
    ): ResponseEntity<ApiResponse<List<DailyStatsResponse>>> {
        val stats = analyticsService.getDailyStats(user.id, start, end)
        return ResponseEntity.ok(ApiResponse(success = true, data = stats))
    }

    @GetMapping("/difficult-words")
    fun getDifficultWords(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "3") minReviews: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<DifficultWordResponse>>> {
        val words = analyticsService.getDifficultWords(user.id, minReviews, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = words))
    }

    @GetMapping("/most-reviewed")
    fun getMostReviewedWords(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<MostReviewedWordResponse>>> {
        val words = analyticsService.getMostReviewedWords(user.id, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = words))
    }

    @GetMapping("/accuracy-by-level")
    fun getAccuracyByLevel(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<AccuracyByLevelResponse>>> {
        val data = analyticsService.getAccuracyByLevel(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/accuracy-by-hour")
    fun getAccuracyByHour(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<HourlyAccuracyResponse>>> {
        val data = analyticsService.getAccuracyByHour(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/accuracy-by-day-of-week")
    fun getAccuracyByDayOfWeek(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<DayOfWeekAccuracyResponse>>> {
        val data = analyticsService.getAccuracyByDayOfWeek(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/sessions")
    fun getRecentSessions(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<ApiResponse<List<StudySessionResponse>>> {
        val sessions = analyticsService.getRecentSessions(user.id, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = sessions))
    }

    @GetMapping("/heatmap")
    fun getHeatmap(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam start: Long,
        @RequestParam end: Long
    ): ResponseEntity<ApiResponse<List<HeatmapDayResponse>>> {
        val data = analyticsService.getHeatmap(user.id, start, end)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/level-transitions")
    fun getLevelTransitions(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<LevelTransitionResponse>>> {
        val data = analyticsService.getLevelTransitions(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/words-mastered")
    fun getWordsMastered(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<ApiResponse<List<MasteredWordResponse>>> {
        val data = analyticsService.getWordsMastered(user.id, limit)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/language-stats")
    fun getLanguageStats(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<LanguagePairStatsResponse>>> {
        val data = analyticsService.getStatsByLanguagePair(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/monthly-stats")
    fun getMonthlyStats(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<MonthlyStatsResponse>>> {
        val data = analyticsService.getMonthlyStats(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/response-time-trend")
    fun getResponseTimeTrend(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<ResponseTimeTrendResponse>>> {
        val data = analyticsService.getResponseTimeTrend(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/comeback-words")
    fun getComebackWords(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<List<ComebackWordResponse>>> {
        val data = analyticsService.getComebackWords(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }

    @GetMapping("/weekly-report")
    fun getWeeklyReport(
        @AuthenticationPrincipal user: AuthUser
    ): ResponseEntity<ApiResponse<WeeklyReportResponse>> {
        val data = analyticsService.getWeeklyReport(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = data))
    }
}
