package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.ApiResponse
import com.alirezaiyan.vokab.server.shared.AuthUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** AI apps connected over MCP, listed and revoked from the app's Settings. Always on. */
@RestController
@RequestMapping("/api/v1/mcp/connections")
class McpConnectionsController(private val connectionService: McpConnectionService) {

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthUser): ResponseEntity<ApiResponse<List<McpConnectionDto>>> =
        ResponseEntity.ok(ApiResponse(success = true, data = connectionService.list(user.id)))

    @DeleteMapping("/{id}")
    fun disconnect(@AuthenticationPrincipal user: AuthUser, @PathVariable id: String): ResponseEntity<Unit> {
        connectionService.disconnect(user.id, id)
        return ResponseEntity.noContent().build()
    }
}
