package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.Word
import com.alirezaiyan.vokab.server.domain.repository.TagRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import com.alirezaiyan.vokab.server.presentation.dto.UpdateWordRequest
import com.alirezaiyan.vokab.server.presentation.dto.WordDto
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import com.alirezaiyan.vokab.server.domain.entity.requireId

@Service
class WordService(
    private val wordRepository: WordRepository,
    private val tagRepository: TagRepository,
    private val wordUpsertPreparer: WordUpsertPreparer,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(user: User): List<WordDto> =
        wordRepository.findAllByUserWithTags(user).map { it.toDto() }

    @Transactional(readOnly = true)
    fun list(user: User, updatedAfter: Instant?): List<WordDto> {
        val words = if (updatedAfter == null) {
            wordRepository.findAllByUserWithTags(user)
        } else {
            wordRepository.findAllByUserAndUpdatedAtAfterWithTags(user, updatedAfter)
        }
        return words.map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun getExistingTranslationKeys(user: User, targetLanguage: String): Set<String> {
        return wordRepository.findTranslationsByUserAndTargetLanguage(user, targetLanguage)
            .map { it.trim().lowercase() }
            .toSet()
    }

    @Transactional
    fun upsert(user: User, words: List<WordDto>) {
        if (words.isEmpty()) return

        val entities = wordUpsertPreparer.prepareUpsertEntities(user, words)
        val hasNewWords = entities.any { it.id == null }
        wordRepository.saveAll(entities)

        if (hasNewWords && user.firstWordAddedAt == null) {
            user.firstWordAddedAt = Instant.now(clock)
            userRepository.save(user)
        }
    }

    @Transactional
    fun update(user: User, id: Long, request: UpdateWordRequest) {
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
    fun delete(user: User, id: Long) {
        val deleted = wordRepository.deleteByIdAndUserId(id, user.requireId())
        require(deleted == 1) { "Word not found" }
    }

    @Transactional
    fun batchDelete(user: User, ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return wordRepository.deleteAllByIdInAndUserId(ids, user.requireId())
    }

    @Transactional
    fun batchUpdateLanguages(
        user: User,
        ids: List<Long>,
        sourceLanguage: String?,
        targetLanguage: String?,
    ): Int {
        if (ids.isEmpty()) return 0
        if (sourceLanguage == null && targetLanguage == null) return 0

        val userId = user.requireId()
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
    fun batchAssignTags(user: User, wordIds: List<Long>, tagIds: List<Long>): Int {
        if (wordIds.isEmpty()) return 0
        val userId = user.requireId()
        wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, userId)
        if (tagIds.isNotEmpty()) {
            wordRepository.insertWordTagsBulkByWordIdsAndUserId(wordIds, tagIds, userId)
        }
        return wordIds.size
    }

    @Transactional
    fun updateWordTags(user: User, wordId: Long, tagIds: List<Long>) {
        val word = wordRepository.findById(wordId).orElseThrow { NoSuchElementException("Word not found") }
        require(word.user?.id == user.id) { "Forbidden" }
        word.tags = if (tagIds.isEmpty()) mutableSetOf()
        else tagRepository.findAllByUserAndIdIn(user, tagIds).toMutableSet()
        wordRepository.save(word)
    }
}

private fun Word.toDto(): WordDto = WordDto(
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
