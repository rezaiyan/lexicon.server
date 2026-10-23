package com.alirezaiyan.vokab.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalManagementPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot tests disable metrics exporters by default; this one checks the real Prometheus endpoint
@AutoConfigureObservability
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthCheckTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health endpoint returns 200`() {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk)
    }

    @LocalManagementPort
    var managementPort: Int = 0

    private fun managementGet(path: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$managementPort$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `actuator is not served on the application port`() {
        // Caddy forwards the application port to the internet; actuator must only be on the internal port
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound)
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound)
    }

    @Test
    fun `management port serves health and prometheus metrics`() {
        assertEquals(200, managementGet("/actuator/health").statusCode())
        assertEquals(200, managementGet("/actuator/health/liveness").statusCode())
        assertEquals(200, managementGet("/actuator/health/readiness").statusCode())

        val metrics = managementGet("/actuator/prometheus")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("jvm_memory_used_bytes"), "prometheus scrape output")
        assertTrue(metrics.body().contains("hikaricp_connections"), "connection pool metrics")
    }
}
