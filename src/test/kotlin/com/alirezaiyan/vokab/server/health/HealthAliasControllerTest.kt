package com.alirezaiyan.vokab.server.health

import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.presentation.controller.HealthController
import com.alirezaiyan.vokab.server.presentation.dto.ApiResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.http.HttpStatus

class HealthAliasControllerTest {

    private val healthEndpoint = mockk<HealthEndpoint>()
    private val controller = HealthController(healthEndpoint, fixedClock())

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Any?) = requireNotNull((body as ApiResponse<Map<String, Any?>>).data)

    @Test
    fun `200 with status UP when the app is ready`() {
        every { healthEndpoint.healthForPath("readiness") } returns Health.up().build()

        val response = controller.health("application/json")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("UP", data(response.body)["status"])
    }

    @Test
    fun `503 when readiness is down, so the Docker healthcheck and deploy script notice`() {
        every { healthEndpoint.healthForPath("readiness") } returns Health.down().build()

        val response = controller.health("application/json")

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("DOWN", data(response.body)["status"])
    }
}
