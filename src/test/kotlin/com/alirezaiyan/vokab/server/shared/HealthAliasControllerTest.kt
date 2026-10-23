package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.fixedClock
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.health.contributor.Status
import org.springframework.http.HttpStatus

class HealthAliasControllerTest {

    private val healthEndpoint = mockk<HealthEndpoint>()
    private val controller = HealthController(healthEndpoint, fixedClock())

    private fun descriptor(status: Status) = mockk<HealthDescriptor> { every { this@mockk.status } returns status }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Any?) = requireNotNull((body as ApiResponse<Map<String, Any?>>).data)

    @Test
    fun `200 with status UP when the app is ready`() {
        every { healthEndpoint.healthForPath("readiness") } returns descriptor(Status.UP)

        val response = controller.health("application/json")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("UP", data(response.body)["status"])
    }

    @Test
    fun `503 when readiness is down, so the Docker healthcheck and deploy script notice`() {
        every { healthEndpoint.healthForPath("readiness") } returns descriptor(Status.DOWN)

        val response = controller.health("application/json")

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("DOWN", data(response.body)["status"])
    }
}
