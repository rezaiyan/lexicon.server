package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.UserFacingException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val MAX_TERM_LENGTH = 200
private const val MAX_NOTE_LENGTH = 1_000
private const val MAX_EXTRACTED_ITEMS = 200

private val extractionMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private data class RawExtractionPayload(val items: List<RawExtractedItem> = emptyList())

private data class RawExtractedItem(val term: String? = null, val translation: String? = null, val note: String? = null)

/**
 * Reads the v2 image-extraction answer (`{"items":[{"term","translation","note"}]}`, possibly wrapped in
 * prose or a code fence) into clean items: trimmed, complete, within length limits, de-duplicated.
 */
internal object ExtractedWordsParser {

    fun parse(raw: String): List<ExtractedWordItem> = sanitize(readItems(raw))

    private fun readItems(raw: String): List<RawExtractedItem> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw UserFacingException("Failed to read vocabulary from the image. Please try a clearer image.")
        }
        return try {
            extractionMapper.readValue(raw.substring(start, end + 1), RawExtractionPayload::class.java).items
        } catch (e: JsonProcessingException) {
            logger.warn { "[AI] Unparseable extraction JSON: ${e.originalMessage}" }
            throw UserFacingException("Failed to read vocabulary from the image. Please try a clearer image.")
        }
    }

    private fun sanitize(items: List<RawExtractedItem>): List<ExtractedWordItem> {
        val seen = mutableSetOf<Pair<String, String>>()
        return items.asSequence()
            .map { raw ->
                ExtractedWordItem(
                    term = raw.term.orEmpty().trim(),
                    translation = raw.translation.orEmpty().trim(),
                    note = raw.note.orEmpty().trim(),
                )
            }
            .filter { it.term.isNotEmpty() && it.translation.isNotEmpty() }
            .filter { it.term.length <= MAX_TERM_LENGTH && it.translation.length <= MAX_TERM_LENGTH }
            .map { if (it.note.length > MAX_NOTE_LENGTH) it.copy(note = "") else it }
            .filter { seen.add(it.term.lowercase() to it.translation.lowercase()) }
            .take(MAX_EXTRACTED_ITEMS)
            .toList()
    }
}
