package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.words.TagService
import com.alirezaiyan.vokab.server.words.WordDto
import com.alirezaiyan.vokab.server.words.WordService
import io.modelcontextprotocol.common.McpTransportContext
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Read-only vocabulary tools for AI clients. Thin: all logic lives in the word services. */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class WordMcpTools(
    private val wordService: WordService,
    private val tagService: TagService,
) {

    @McpTool(
        name = "search_words",
        description = "Search the learner's saved vocabulary. Matches the word or its translation " +
            "(case-insensitive). Optionally narrow by language code (e.g. \"de\") or tag name.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false),
    )
    fun searchWords(
        context: McpTransportContext,
        @McpToolParam(description = "Text to find in the word or translation; empty lists all", required = false)
        query: String?,
        @McpToolParam(description = "Language code the word is in, e.g. \"de\"", required = false)
        language: String?,
        @McpToolParam(description = "Only words with this tag name", required = false)
        tag: String?,
        @McpToolParam(description = "Max results, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)", required = false)
        limit: Int?,
    ): List<WordResult> {
        val userId = context.userIdWithScope(McpScopes.WORDS_READ)
        val tagId = tag?.takeIf { it.isNotBlank() }?.let { name ->
            tagService.list(userId).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
                ?: return emptyList()
        }
        val needle = query?.trim()?.lowercase().orEmpty()
        val lang = language?.trim()?.takeIf { it.isNotEmpty() }
        return wordService.list(userId).asSequence()
            .filter { word ->
                needle.isEmpty() || needle in word.originalWord.lowercase() || needle in word.translation.lowercase()
            }
            .filter { lang == null || it.sourceLanguage.equals(lang, true) || it.targetLanguage.equals(lang, true) }
            .filter { tagId == null || tagId in it.tagIds }
            .take((limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
            .map { it.toResult() }
            .toList()
    }

    private fun WordDto.toResult() = WordResult(
        id = id,
        word = originalWord,
        translation = translation,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        level = level,
        note = description.ifBlank { null },
    )

    @McpTool(
        name = "list_tags",
        description = "List the learner's tags with how many words each has.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false),
    )
    fun listTags(context: McpTransportContext): List<TagResult> =
        tagService.list(context.userIdWithScope(McpScopes.WORDS_READ)).map { TagResult(it.id, it.name, it.wordCount) }

    data class WordResult(
        val id: Long?,
        val word: String,
        val translation: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val level: Int,
        val note: String?,
    )

    data class TagResult(val id: Long, val name: String, val wordCount: Long)

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}
