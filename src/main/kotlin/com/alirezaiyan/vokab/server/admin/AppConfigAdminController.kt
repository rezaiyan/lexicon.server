package com.alirezaiyan.vokab.server.admin

import com.alirezaiyan.vokab.server.shared.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/admin/config")
class AppConfigAdminController(
    private val appConfigService: AppConfigService
) {
    @GetMapping
    fun list(): ResponseEntity<ApiResponse<List<AppConfigDto>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = appConfigService.list().map { it.toDto() }))

    @GetMapping("/{namespace}/{key}")
    fun get(
        @PathVariable namespace: String,
        @PathVariable key: String
    ): ResponseEntity<ApiResponse<AppConfigDto>> {
        val config = appConfigService.find(namespace, key)
            ?: throw NoSuchElementException("Config not found: $namespace/$key")
        return ResponseEntity.ok(ApiResponse(success = true, data = config.toDto()))
    }

    @PutMapping("/{namespace}/{key}")
    fun set(
        @PathVariable namespace: String,
        @PathVariable key: String,
        @Valid @RequestBody request: AppConfigUpdateRequest,
        @RequestHeader(value = "X-Changed-By", required = false) changedBy: String?,
        @AuthenticationPrincipal admin: Any?,
    ): ResponseEntity<ApiResponse<AppConfigDto>> {
        val updated = appConfigService.set(namespace, key, request.value, auditActor(admin, changedBy))
        return ResponseEntity.ok(ApiResponse(success = true, data = updated.toDto()))
    }

    @PostMapping("/{namespace}/{key}/items")
    fun addItem(
        @PathVariable namespace: String,
        @PathVariable key: String,
        @Valid @RequestBody request: AppConfigListItemRequest,
        @RequestHeader(value = "X-Changed-By", required = false) changedBy: String?,
        @AuthenticationPrincipal admin: Any?,
    ): ResponseEntity<ApiResponse<AppConfigDto>> {
        val updated = appConfigService.addListItem(namespace, key, request.item, auditActor(admin, changedBy))
        return ResponseEntity.ok(ApiResponse(success = true, data = updated.toDto()))
    }

    @DeleteMapping("/{namespace}/{key}/items/{item}")
    fun removeItem(
        @PathVariable namespace: String,
        @PathVariable key: String,
        @PathVariable item: String,
        @RequestHeader(value = "X-Changed-By", required = false) changedBy: String?,
        @AuthenticationPrincipal admin: Any?,
    ): ResponseEntity<ApiResponse<AppConfigDto>> {
        val updated = appConfigService.removeListItem(namespace, key, item, auditActor(admin, changedBy))
        return ResponseEntity.ok(ApiResponse(success = true, data = updated.toDto()))
    }

    @GetMapping("/{namespace}/{key}/history")
    fun history(
        @PathVariable namespace: String,
        @PathVariable key: String
    ): ResponseEntity<ApiResponse<List<AppConfigHistoryDto>>> =
        ResponseEntity.ok(
            ApiResponse(success = true, data = appConfigService.history(namespace, key).map { it.toDto() })
        )
}

/**
 * Who to record in the config history: the authenticated admin principal (CLI key or dashboard
 * session), never a client header alone. `X-Changed-By` is kept as a free-text note, labelled as
 * client-supplied, since anyone holding the admin key can put any name there.
 */
internal fun auditActor(principal: Any?, clientNote: String?): String {
    val actor = principal?.toString()?.takeIf { it.isNotBlank() } ?: "admin"
    val note = clientNote?.filterNot { it.isISOControl() }?.trim()?.take(MAX_CLIENT_NOTE)?.takeIf { it.isNotEmpty() }
    return if (note == null) actor else "$actor (client-supplied: $note)"
}

private const val MAX_CLIENT_NOTE = 64
