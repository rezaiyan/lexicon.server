package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.AuditLogService
import com.alirezaiyan.vokab.server.words.NewWord
import com.alirezaiyan.vokab.server.words.TagService
import com.alirezaiyan.vokab.server.words.WordAdditionService
import com.alirezaiyan.vokab.server.words.WordDto
import com.alirezaiyan.vokab.server.words.WordQueryService
import com.alirezaiyan.vokab.server.words.WordService
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.modelcontextprotocol.common.McpTransportContext
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Vocabulary tools for AI clients. Thin: all logic lives in the word services.
 * Nothing here calls the backend's AI endpoints: the AI client does any extracting or translating
 * itself, so these tools cost no AI credits.
 */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class WordMcpTools(
    private val wordService: WordService,
    private val wordQueryService: WordQueryService,
    private val wordAdditionService: WordAdditionService,
    private val tagService: TagService,
    private val auditLog: AuditLogService,
    private val deleteLimiter: McpDeleteLimiter,
) {

    @McpTool(
        name = "search_words",
        description = "Search the learner's saved vocabulary. Matches the word or its translation " +
            "(case-insensitive). Optionally narrow by the language being learned (code, e.g. \"de\") or a tag name. " +
            "Results come in pages: when nextCursor is present, call again with cursor set to it for the rest.",
        title = "Search my Lexicon words",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun searchWords(
        context: McpTransportContext,
        @McpToolParam(description = "Text to find in the word or translation; empty lists all", required = false)
        query: String?,
        @McpToolParam(description = "Code of the language being learned, e.g. \"de\"", required = false)
        language: String?,
        @McpToolParam(description = "Only words with this tag name", required = false)
        tag: String?,
        @McpToolParam(description = "Max results per page, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)", required = false)
        limit: Int?,
        @McpToolParam(
            description = "true for the most recently saved words first (default oldest first)",
            required = false,
        )
        newestFirst: Boolean?,
        @McpToolParam(
            description = "nextCursor from the previous result, to get the next page (same other arguments)",
            required = false,
        )
        cursor: Long?,
    ): WordList {
        val userId = context.userIdWithScope(McpScopes.WORDS_READ)
        val tagId = tag?.takeIf { it.isNotBlank() }?.let { name ->
            tagService.list(userId).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
                ?: return WordList(emptyList(), nextCursor = null)
        }
        val page = wordQueryService.search(userId, query, language, tagId, limit.clamped(), cursor, newestFirst == true)
        return WordList(page.words.map { it.toResult() }, page.nextCursor)
    }

    @McpTool(
        name = "list_tags",
        description = "List the learner's tags with how many words each has.",
        title = "List my Lexicon tags",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun listTags(context: McpTransportContext): TagList = TagList(
        tagService.list(context.userIdWithScope(McpScopes.WORDS_READ)).map { TagResult(it.id, it.name, it.wordCount) }
    )

    @McpTool(
        name = "get_due_words",
        description = "Words due for review now, most overdue first, plus how many are due in total. " +
            "Use them to quiz the learner. Answers given in chat don't count as reviews in the app.",
        title = "Get my due Lexicon words",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false),
        generateOutputSchema = true,
    )
    fun getDueWords(
        context: McpTransportContext,
        @McpToolParam(description = "Max words, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)", required = false)
        limit: Int?,
    ): DueWords {
        val userId = context.userIdWithScope(McpScopes.WORDS_READ)
        val due = wordQueryService.listDue(userId, limit.clamped())
        return DueWords(dueCount = wordQueryService.countDue(userId).toInt(), words = due.map { it.toResult() })
    }

    @McpTool(
        name = "add_words",
        description = "Add words to the learner's vocabulary as new flashcards, due for review right away. " +
            "Words they already have (same word and translation in the same language) are skipped and keep " +
            "their progress. Tags are names; missing tags are created. The app shows new words the next time " +
            "it opens.",
        title = "Add words to Lexicon",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false,
        ),
        generateOutputSchema = true,
    )
    fun addWords(
        context: McpTransportContext,
        @McpToolParam(description = "Words to add, at most $MAX_BATCH per call") words: List<WordInput>,
    ): AddedWords {
        val userId = context.userIdWithScope(McpScopes.WORDS_WRITE)
        require(words.isNotEmpty() && words.size <= MAX_BATCH) { "Send between 1 and $MAX_BATCH words" }
        val result = wordAdditionService.addNew(userId, words.map { it.toNewWord() })
        if (result.added.isNotEmpty()) {
            auditLog.logMcpWrite(userId, context.clientId(), "add_words", result.added.mapNotNull { it.id })
        }
        return AddedWords(
            added = result.added.map { AddedWord(requireNotNull(it.id), it.originalWord, it.translation) },
            skipped = result.skipped,
        )
    }

    @McpTool(
        name = "tag_words",
        description = "Add tags (by name) to some of the learner's words, keeping the tags they already have. " +
            "Missing tags are created. Get word ids from search_words or get_due_words.",
        title = "Tag Lexicon words",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false,
        ),
        generateOutputSchema = true,
    )
    fun tagWords(
        context: McpTransportContext,
        @McpToolParam(description = "Ids of the words to tag, at most $MAX_BATCH") wordIds: List<Long>,
        @McpToolParam(description = "Tag names to add, at most $MAX_TAGS") tags: List<String>,
    ): TaggedWords {
        val userId = context.userIdWithScope(McpScopes.WORDS_WRITE)
        require(wordIds.isNotEmpty() && wordIds.size <= MAX_BATCH) { "Send between 1 and $MAX_BATCH word ids" }
        val tagged = wordAdditionService.addTags(userId, wordIds, validTags(tags))
        if (tagged > 0) auditLog.logMcpWrite(userId, context.clientId(), "tag_words", wordIds)
        return TaggedWords(tagged = tagged)
    }

    @McpTool(
        name = "delete_words",
        description = "Permanently delete some of the learner's words, with their review progress, on all " +
            "their devices. Ids that aren't the learner's are ignored. Confirm with the learner first. " +
            "At most ${McpDeleteLimiter.WORDS_PER_HOUR} words can be deleted per hour. " +
            "Get word ids from search_words or get_due_words.",
        title = "Delete Lexicon words",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false,
        ),
        generateOutputSchema = true,
    )
    fun deleteWords(
        context: McpTransportContext,
        @McpToolParam(description = "Ids of the words to delete, at most $MAX_BATCH") wordIds: List<Long>,
    ): DeletedWordIds {
        val userId = context.userIdWithScope(McpScopes.WORDS_DELETE)
        require(wordIds.isNotEmpty() && wordIds.size <= MAX_BATCH) { "Send between 1 and $MAX_BATCH word ids" }
        deleteLimiter.acquire(userId, wordIds.distinct().size)
        val deleted = wordService.deleteOwned(userId, wordIds)
        if (deleted.isNotEmpty()) auditLog.logMcpWrite(userId, context.clientId(), "delete_words", deleted)
        return DeletedWordIds(deleted = deleted, notFound = wordIds.distinct() - deleted.toSet())
    }

    private fun WordInput.toNewWord(): NewWord {
        require(word.isNotBlank() && word.length <= MAX_TEXT) { "Each word needs 1-$MAX_TEXT characters" }
        require(translation.isNotBlank() && translation.length <= MAX_TEXT) {
            "Each translation needs 1-$MAX_TEXT characters (word \"$word\")"
        }
        require((note?.length ?: 0) <= MAX_NOTE) { "Notes are limited to $MAX_NOTE characters (word \"$word\")" }
        return NewWord(
            term = word,
            translation = translation,
            note = note.orEmpty(),
            learningLanguage = McpLanguages.require(language, "language"),
            nativeLanguage = translationLanguage?.takeIf { it.isNotBlank() }
                ?.let { McpLanguages.require(it, "translationLanguage") },
            tagNames = validTags(tags.orEmpty()),
        )
    }

    private fun validTags(tags: List<String>): List<String> {
        val names = tags.map { it.trim() }.filter { it.isNotEmpty() }
        require(names.size <= MAX_TAGS) { "At most $MAX_TAGS tags at once" }
        require(names.all { it.length <= MAX_TAG_NAME }) { "Tag names are limited to $MAX_TAG_NAME characters" }
        return names
    }

    private fun Int?.clamped() = (this ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    private fun WordDto.toResult() = WordResult(
        id = requireNotNull(id),
        word = originalWord,
        translation = translation,
        language = targetLanguage,
        translationLanguage = sourceLanguage,
        level = level,
        note = description.ifBlank { null },
    )

    /**
     * One word to add. [language] is the language being learned, the one [word] is in.
     * Explicit creator: the MCP library parses arguments with its own Jackson mapper, which has no
     * Kotlin module. The field annotations drive the JSON schema clients see (required unless marked).
     */
    data class WordInput @JsonCreator constructor(
        @param:JsonProperty("word", required = true)
        @field:JsonPropertyDescription("The word or phrase, in the language being learned")
        val word: String,
        @param:JsonProperty("translation", required = true)
        @field:JsonPropertyDescription("Its meaning in the learner's own language")
        val translation: String,
        @param:JsonProperty("language", required = true)
        @field:JsonPropertyDescription("Code of the language being learned, e.g. \"de\"")
        val language: String,
        @param:JsonProperty("translationLanguage")
        @field:JsonProperty("translationLanguage", required = false)
        @field:JsonPropertyDescription("Code of the translation's language; defaults to the learner's usual one")
        val translationLanguage: String? = null,
        @param:JsonProperty("note")
        @field:JsonProperty("note", required = false)
        @field:JsonPropertyDescription("Optional note: example sentence, gender, grammar")
        val note: String? = null,
        @param:JsonProperty("tags")
        @field:JsonProperty("tags", required = false)
        @field:JsonPropertyDescription("Optional tag names")
        val tags: List<String>? = null,
    )

    /**
     * [language] is the language being learned; [level] runs from 0 (new) to 6 (mastered).
     * Optional fields are left out rather than sent as null: the output schema can't express Kotlin nullability.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WordResult(
        val id: Long,
        val word: String,
        val translation: String,
        val language: String,
        val translationLanguage: String,
        val level: Int,
        @field:JsonProperty(required = false)
        val note: String?,
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WordList(
        val words: List<WordResult>,
        /** Present when more words match: pass it back as `cursor` for the next page. */
        @field:JsonProperty(required = false)
        val nextCursor: Long?,
    )
    data class TagResult(val id: Long, val name: String, val wordCount: Long)
    data class TagList(val tags: List<TagResult>)
    data class DueWords(val dueCount: Int, val words: List<WordResult>)
    data class AddedWord(val id: Long, val word: String, val translation: String)
    data class AddedWords(val added: List<AddedWord>, val skipped: List<String>)
    data class TaggedWords(val tagged: Int)
    data class DeletedWordIds(val deleted: List<Long>, val notFound: List<Long>)

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val MAX_BATCH = 100
        const val MAX_TAGS = 10
        const val MAX_TAG_NAME = 100
        const val MAX_TEXT = 200
        const val MAX_NOTE = 1000
    }
}
