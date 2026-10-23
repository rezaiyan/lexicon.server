package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.fixedClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

@DataJpaTest
@ActiveProfiles("test")
class JpaAiUsageRecorderTest {

    @Autowired private lateinit var repository: AiUsageRepository

    private val meterRegistry = SimpleMeterRegistry()

    @Test
    fun `a call is stored with its tokens and cost and counted in the cost summary`() {
        val recorder = JpaAiUsageRecorder(repository, meterRegistry, fixedClock())

        recorder.record(AiOperation.SUGGEST_VOCABULARY, "anthropic/claude-haiku-4.5", AiUsageReport(800, 2100, BigDecimal("0.0113")))

        val row = repository.findAll().single()
        assertEquals(AiOperation.SUGGEST_VOCABULARY, row.operation)
        assertEquals("anthropic/claude-haiku-4.5", row.model)
        assertEquals(800, row.promptTokens)
        assertEquals(2100, row.completionTokens)
        assertEquals(0, BigDecimal("0.0113").compareTo(row.costUsd))
        val summary = meterRegistry.get("ai.cost.usd").tag("operation", "suggest vocabulary").summary()
        assertEquals(0.0113, summary.totalAmount(), 1e-9)
    }

    @Test
    fun `a call without reported usage is still stored`() {
        val recorder = JpaAiUsageRecorder(repository, meterRegistry, fixedClock())

        recorder.record(AiOperation.TRANSLATION, "m", AiUsageReport(null, null, null))

        assertNull(repository.findAll().single().costUsd)
    }

    @Test
    fun `a storage failure never reaches the AI call`() {
        val failing = mockk<AiUsageRepository> { every { save(any()) } throws IllegalStateException("db down") }

        JpaAiUsageRecorder(failing, meterRegistry, fixedClock())
            .record(AiOperation.TRANSLATION, "m", AiUsageReport(1, 1, BigDecimal.ONE))
    }
}
