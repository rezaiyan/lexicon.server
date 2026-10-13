package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Comeback rescue taste test and funnel for the admin page; SecurityConfig limits /admin to ROLE_ADMIN. */
@RestController
@RequestMapping("/admin/rescue")
class RescueAdminController(
    private val previewService: RescuePreviewService,
    private val adminService: RescueAdminService,
    private val objectMapper: ObjectMapper,
) {
    /** Drafts copy for up to [count] random learners with AI calls; nothing is sent or planned. */
    @PostMapping("/previews")
    fun generatePreviews(
        @RequestParam(defaultValue = "20") count: Int,
    ): ResponseEntity<ApiResponse<List<RescuePreviewView>>> {
        val views = adminService.previewViews(previewService.generate(count))
        return ResponseEntity.ok(ApiResponse(success = true, data = views))
    }

    @GetMapping("/previews")
    fun previews(@RequestParam(defaultValue = "50") limit: Int): ResponseEntity<ApiResponse<List<RescuePreviewView>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = adminService.previews(limit)))

    @PostMapping("/previews/{previewId}/ratings")
    fun rate(
        @PathVariable previewId: Long,
        @Valid @RequestBody request: RescueRatingRequest,
    ): ResponseEntity<ApiResponse<TasteScore>> {
        previewService.rate(previewId, request.candidateIndex, request.rating, request.note)
        return ResponseEntity.ok(ApiResponse(success = true, data = previewService.tasteScore()))
    }

    @GetMapping("/taste")
    fun taste(): ResponseEntity<ApiResponse<TasteScore>> =
        ResponseEntity.ok(ApiResponse(success = true, data = previewService.tasteScore()))

    @GetMapping("/plans")
    fun plans(@RequestParam(defaultValue = "50") limit: Int): ResponseEntity<ApiResponse<List<RescuePlanView>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = adminService.plans(limit)))

    @GetMapping("/funnel")
    fun funnel(@RequestParam(defaultValue = "14") days: Int): ResponseEntity<ApiResponse<List<RescueFunnelRow>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = adminService.funnel(days)))

    /** Rated candidates as JSON lines: the regression set for the next prompt version. */
    @GetMapping("/eval.jsonl", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun evalSet(): ResponseEntity<String> =
        ResponseEntity.ok(adminService.evalSet().joinToString("\n") { objectMapper.writeValueAsString(it) })
}
