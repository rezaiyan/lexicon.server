package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/api/v1/words")
class WordController(
    private val wordService: WordService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthUser,
        @RequestParam(required = false) updatedAfter: Long?,
    ): ResponseEntity<ApiResponse<List<WordDto>>> {
        val since = updatedAfter?.let { Instant.ofEpochMilli(it) }
        val words = wordService.list(user.id, since)
        return ResponseEntity.ok(ApiResponse(success = true, data = words))
    }

    @PostMapping
    fun upsert(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody req: UpsertWordsRequest,
    ): ResponseEntity<ApiResponse<List<WordDto>>> {
        val saved = wordService.upsert(user.id, req.words)
        return ResponseEntity.ok(ApiResponse(success = true, data = saved, message = "Upserted"))
    }

    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateWordRequest,
    ): ResponseEntity<ApiResponse<Unit>> {
        wordService.update(user.id, id, request)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Updated"))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
    ): ResponseEntity<ApiResponse<Unit>> {
        wordService.delete(user.id, id)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Deleted"))
    }

    @PostMapping("/batch-delete")
    fun batchDelete(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: BatchDeleteRequest,
    ): ResponseEntity<ApiResponse<BatchDeleteResponse>> {
        val deletedCount = wordService.batchDelete(user.id, request.ids)
        return ResponseEntity.ok(
            ApiResponse(
                success = true,
                message = "Deleted $deletedCount words",
                data = BatchDeleteResponse(deletedCount = deletedCount),
            )
        )
    }

    @PutMapping("/{id}/tags")
    fun updateTags(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateWordTagsRequest,
    ): ResponseEntity<ApiResponse<Unit>> {
        wordService.updateWordTags(user.id, id, request.tagIds)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Tags updated"))
    }

    @PostMapping("/batch-assign-tags")
    fun batchAssignTags(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: BatchAssignTagsRequest,
    ): ResponseEntity<ApiResponse<Unit>> {
        wordService.batchAssignTags(user.id, request.wordIds, request.tagIds)
        return ResponseEntity.ok(ApiResponse(success = true, message = "Tags assigned"))
    }

    @PostMapping("/batch-update")
    fun batchUpdate(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: BatchUpdateLanguagesRequest,
    ): ResponseEntity<ApiResponse<BatchUpdateLanguagesResponse>> {
        val updatedCount = wordService.batchUpdateLanguages(
            user.id, request.ids, request.sourceLanguage, request.targetLanguage,
        )
        return ResponseEntity.ok(
            ApiResponse(
                success = true,
                message = "Updated $updatedCount words",
                data = BatchUpdateLanguagesResponse(updatedCount = updatedCount),
            )
        )
    }
}
