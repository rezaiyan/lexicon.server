package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.UserRepository
import java.time.Clock
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WordService(
    private val wordRepository: WordRepository,
    private val tagRepository: TagRepository,
    private val wordUpsertPreparer: WordUpsertPreparer,
    private val userRepository: UserRepository,
    private val clock: Clock,
    private val deletedWords: DeletedWords,
) {
    @Transactional(readOnly = true)
    fun list(userId: Long): List<WordDto> = list(userId, null)

    @Transactional(readOnly = true)
    fun list(userId: Long, updatedAfter: Instant?): List<WordDto> {
        val user = userRepository.getReferenceById(userId)
        val words = if (updatedAfter == null) {
            wordRepository.findAllByUserWithTags(user)
        } else {
            wordRepository.findAllByUserAndUpdatedAtAfterWithTags(user, updatedAfter)
        }
        return words.map { it.toDto() }
    }

    /** Words due for review now, most overdue first. */
    @Transactional(readOnly = true)
    fun listDue(userId: Long, limit: Int): List<WordDto> {
        val ids = wordRepository.findDueIds(userId, Instant.now(clock).toEpochMilli(), PageRequest.of(0, limit))
        return loadInOrder(ids)
    }

    @Transactional(readOnly = true)
    fun countDue(userId: Long): Long = wordRepository.countDue(userId, Instant.now(clock).toEpochMilli())

    /**
     * A user's words whose term or translation contains [query] (case-insensitive), optionally only
     * in learning language [language] and with tag [tagId]; at most [limit], filtered in the database.
     */
    @Transactional(readOnly = true)
    /** One page of a search plus where the next page starts, null on the last page. */
    data class SearchPage(val words: List<WordDto>, val nextCursor: Long?)

    /**
     * Words matching [query] (word or translation), [language] and [tagId], [limit] at a time in id
     * order. [cursor] is the previous page's [SearchPage.nextCursor]; ids only ever grow, so pages
     * stay stable while words are added.
     */
    fun search(
        userId: Long,
        query: String?,
        language: String?,
        tagId: Long?,
        limit: Int,
        cursor: Long? = null,
        newestFirst: Boolean = false,
    ): SearchPage {
        val needle = query?.trim()?.lowercase().orEmpty()
        val pattern = "%" + needle.replace("#", "##").replace("%", "#%").replace("_", "#_") + "%"
        val lang = language?.trim()?.lowercase().orEmpty()
        val page = PageRequest.of(0, limit + 1)
        val ids = if (newestFirst) {
            wordRepository.searchIdsNewestFirst(userId, pattern, lang, tagId ?: 0L, cursor ?: 0L, page)
        } else {
            wordRepository.searchIds(userId, pattern, lang, tagId ?: 0L, cursor ?: 0L, page)
        }
        val pageIds = ids.take(limit)
        return SearchPage(loadInOrder(pageIds), nextCursor = pageIds.lastOrNull()?.takeIf { ids.size > limit })
    }

    private fun loadInOrder(ids: List<Long>): List<WordDto> {
        if (ids.isEmpty()) return emptyList()
        val byId = wordRepository.findAllByIdInWithTags(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDto() }
    }

    /** Lower-cased terms the user already has in [targetLanguage] (stored as a language code, e.g. "de"). */
    @Transactional(readOnly = true)
    fun getExistingTermKeys(userId: Long, targetLanguage: String): Set<String> {
        val user = userRepository.getReferenceById(userId)
        return wordRepository.findTermsByUserAndTargetLanguage(user, targetLanguage)
            .map { it.trim().lowercase() }
            .toSet()
    }

    @Transactional
    /** Saves [words] (matched by id or content) and returns them as stored, with their server ids. */
    fun upsert(userId: Long, words: List<WordDto>): List<WordDto> {
        val user = userRepository.getReferenceById(userId)
        if (words.isEmpty()) return emptyList()

        val entities = wordUpsertPreparer.prepareUpsertEntities(user, words)
        val hasNewWords = entities.any { it.id == null }
        val saved = wordRepository.saveAll(entities)

        if (hasNewWords && user.firstWordAddedAt == null) {
            user.firstWordAddedAt = Instant.now(clock)
            userRepository.save(user)
        }
        return saved.map { it.toDto() }
    }

    @Transactional
    fun update(userId: Long, id: Long, request: UpdateWordRequest) {
        val user = userRepository.getReferenceById(userId)
        val entity = wordRepository.findById(id).orElseThrow()
        require(entity.user?.id == user.id) { "Forbidden" }
        entity.originalWord = request.originalWord
        entity.translation = request.translation
        entity.description = request.description
        entity.sourceLanguage = request.sourceLanguage
        entity.targetLanguage = request.targetLanguage
        entity.level = request.level
        entity.easeFactor = request.easeFactor
        entity.interval = request.interval
        entity.repetitions = request.repetitions
        entity.lastReviewDate = request.lastReviewDate
        entity.nextReviewDate = request.nextReviewDate
        entity.updatedAt = Instant.now(clock)
        // Only update tags when the client explicitly provides them; empty list means "no tag info"
        if (request.tagIds.isNotEmpty()) {
            entity.tags = tagRepository.findAllByUserAndIdIn(user, request.tagIds).toMutableSet()
        }
        wordRepository.save(entity)
    }

    @Transactional
    fun delete(userId: Long, id: Long) {
        val deleted = wordRepository.deleteByIdAndUserId(id, userId)
        require(deleted == 1) { "Word not found" }
        deletedWords.record(userId, listOf(id))
    }

    /** Deletes those of [ids] the user owns and returns their ids; the rest are ignored. */
    @Transactional
    fun deleteOwned(userId: Long, ids: Collection<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val owned = wordRepository.findOwnedIds(ids.distinct(), userId)
        if (owned.isEmpty()) return emptyList()
        wordRepository.deleteAllByIdInAndUserId(owned, userId)
        deletedWords.record(userId, owned)
        return owned
    }

    @Transactional
    fun batchDelete(userId: Long, ids: List<Long>): Int = deleteOwned(userId, ids).size

    /** Ids of the user's words deleted after [since], so a device can drop them too. */
    @Transactional(readOnly = true)
    fun deletedSince(userId: Long, since: Instant): List<Long> = deletedWords.since(userId, since)

    @Transactional
    fun batchUpdateLanguages(
        userId: Long,
        ids: List<Long>,
        sourceLanguage: String?,
        targetLanguage: String?,
    ): Int {
        if (ids.isEmpty()) return 0
        if (sourceLanguage == null && targetLanguage == null) return 0

        val userId = userId
        val now = Instant.now(clock)

        return when {
            sourceLanguage != null && targetLanguage != null ->
                wordRepository.updateLanguagesByIdInAndUserId(ids, userId, sourceLanguage, targetLanguage, now)

            sourceLanguage != null ->
                wordRepository.updateSourceLanguageByIdInAndUserId(ids, userId, sourceLanguage, now)

            targetLanguage != null ->
                wordRepository.updateTargetLanguageByIdInAndUserId(ids, userId, targetLanguage, now)

            else -> 0
        }
    }

    @Transactional
    fun batchAssignTags(userId: Long, wordIds: List<Long>, tagIds: List<Long>): Int {
        if (wordIds.isEmpty()) return 0
        val userId = userId
        wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, userId)
        if (tagIds.isNotEmpty()) {
            wordRepository.insertWordTagsBulkByWordIdsAndUserId(wordIds, tagIds, userId)
        }
        wordRepository.touchByIdInAndUserId(wordIds, userId, Instant.now(clock))
        return wordIds.size
    }

    @Transactional
    fun updateWordTags(userId: Long, wordId: Long, tagIds: List<Long>) {
        val user = userRepository.getReferenceById(userId)
        val word = wordRepository.findById(wordId).orElseThrow { NoSuchElementException("Word not found") }
        require(word.user?.id == user.id) { "Forbidden" }
        word.tags = if (tagIds.isEmpty()) mutableSetOf()
        else tagRepository.findAllByUserAndIdIn(user, tagIds).toMutableSet()
        word.updatedAt = Instant.now(clock)
        wordRepository.save(word)
    }
}

internal fun Word.toDto(): WordDto = WordDto(
    id = id,
    originalWord = originalWord,
    translation = translation,
    description = description,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    level = level,
    easeFactor = easeFactor,
    interval = interval,
    repetitions = repetitions,
    lastReviewDate = lastReviewDate,
    nextReviewDate = nextReviewDate,
    tagIds = tags.mapNotNull { it.id },
    updatedAt = updatedAt.toEpochMilli(),
)
