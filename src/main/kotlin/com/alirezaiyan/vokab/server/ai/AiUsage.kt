package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.JpaEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** What one model call consumed, as the provider reported it; any field may be missing. */
data class AiUsageReport(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val costUsd: BigDecimal?,
)

/** Where [OpenRouterClient] reports each answered call. */
fun interface AiUsageRecorder {
    fun record(operation: AiOperation, model: String, usage: AiUsageReport)
}

/** One answered model call. Append-only; read in Metabase to price AI credits. */
@Entity
@Table(name = "ai_usage")
class AiUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val operation: AiOperation,

    @Column(nullable = false, length = 100)
    val model: String,

    @Column(name = "prompt_tokens")
    val promptTokens: Int? = null,

    @Column(name = "completion_tokens")
    val completionTokens: Int? = null,

    @Column(name = "cost_usd", precision = 12, scale = 8)
    val costUsd: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) : JpaEntity<Long>()

@Repository
interface AiUsageRepository : JpaRepository<AiUsage, Long>

/**
 * Stores each call in `ai_usage` and adds its cost to the `ai.cost.usd{operation}` summary.
 * Bookkeeping only: a failure here is logged and never fails the AI call it describes.
 */
@Component
class JpaAiUsageRecorder(
    private val repository: AiUsageRepository,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) : AiUsageRecorder {

    override fun record(operation: AiOperation, model: String, usage: AiUsageReport) {
        usage.costUsd?.let {
            DistributionSummary.builder("ai.cost.usd").tag("operation", operation.tag)
                .register(meterRegistry).record(it.toDouble())
        }
        runCatching {
            repository.save(
                AiUsage(
                    operation = operation,
                    model = model,
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    costUsd = usage.costUsd,
                    createdAt = Instant.now(clock),
                )
            )
        }.onFailure { logger.warn(it) { "Could not record AI usage for ${operation.tag}" } }
    }
}
