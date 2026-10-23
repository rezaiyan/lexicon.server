package com.alirezaiyan.vokab.server.notification.rescue

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

@JsonIgnoreProperties(ignoreUnknown = true)
data class RescueCopyCandidate(
    val angle: String = "",
    val title: String = "",
    val body: String = "",
    val factIds: List<String> = emptyList(),
    /** The model's own preference, 1 = best. */
    val rank: Int = Int.MAX_VALUE,
)

/** The model's answer to the comeback-rescue prompt. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RescueAiResponse(
    val wordIds: List<Long> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    val candidates: List<RescueCopyCandidate> = emptyList(),
    val outroWin: String? = null,
    val outroSoft: String? = null,
) {
    companion object {
        /** Null when [raw] is not the expected JSON; the planner then falls back. */
        fun parse(raw: String, objectMapper: ObjectMapper): RescueAiResponse? {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return runCatching { objectMapper.readValue(json, RescueAiResponse::class.java) }
                .onFailure { logger.warn { "Unparsable comeback rescue response: ${it.message}" } }
                .getOrNull()
        }
    }
}
