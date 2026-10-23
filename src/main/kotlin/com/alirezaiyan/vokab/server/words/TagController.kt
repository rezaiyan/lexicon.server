package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/tags")
class TagController(private val tagService: TagService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal user: AuthUser,
    ): ResponseEntity<ApiResponse<List<TagDto>>> {
        val tags = tagService.list(user.id)
        return ResponseEntity.ok(ApiResponse(success = true, data = tags))
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: CreateTagRequest,
    ): ResponseEntity<ApiResponse<TagDto>> {
        val tag = tagService.create(user.id, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse(success = true, data = tag))
    }

    @PutMapping("/{id}")
    fun rename(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: RenameTagRequest,
    ): ResponseEntity<ApiResponse<TagDto>> {
        val tag = tagService.rename(user.id, id, request.name)
        return ResponseEntity.ok(ApiResponse(success = true, data = tag))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable id: Long,
    ): ResponseEntity<Unit> {
        tagService.delete(user.id, id)
        return ResponseEntity.noContent().build()
    }
}
