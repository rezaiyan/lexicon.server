package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.Tag
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.entity.Word
import com.alirezaiyan.vokab.server.domain.repository.TagRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import com.alirezaiyan.vokab.server.presentation.dto.WordDto
import org.springframework.stereotype.Component

@Component
class WordUpsertPreparer(
    private val wordRepository: WordRepository,
    private val tagRepository: TagRepository,
) {
    fun prepareUpsertEntities(user: User, words: List<WordDto>): Collection<Word> {
        if (words.isEmpty()) return emptyList()

        val existingByKey = loadExistingByKey(user, words)
        val tagById = loadTagsById(user, words)
        return buildEntitiesToSave(user, words, existingByKey, tagById).values
    }

    private fun loadExistingByKey(user: User, words: List<WordDto>): Map<WordKey, Word> {
        val knownIds = words.mapNotNull { it.id }
        val existing = if (knownIds.size == words.size) {
            // All words have IDs: fetch only those rows
            wordRepository.findAllByUserAndIdIn(user, knownIds)
        } else {
            // Some words are new (no ID): must full-scan to detect duplicates by content
            wordRepository.findAllByUser(user)
        }
        return existing.associateBy { WordKey(it.originalWord, it.translation) }
    }

    /** Batch-loads all tags referenced by any DTO in the request (single query). */
    private fun loadTagsById(user: User, words: List<WordDto>): Map<Long, Tag> {
        val allTagIds = words.flatMapTo(mutableSetOf()) { it.tagIds }
        if (allTagIds.isEmpty()) return emptyMap()
        return tagRepository.findAllByUserAndIdIn(user, allTagIds.toList())
            .associateBy { checkNotNull(it.id) }
    }

    private fun buildEntitiesToSave(
        user: User,
        words: List<WordDto>,
        existingByKey: Map<WordKey, Word>,
        tagById: Map<Long, Tag>,
    ): LinkedHashMap<WordKey, Word> {
        val result = LinkedHashMap<WordKey, Word>()
        for (dto in words) {
            val key = WordKey(dto.originalWord, dto.translation)
            val entity = existingByKey[key]?.let { updateEntityFromDto(it, dto, tagById) }
                ?: createEntityFromDto(user, dto, tagById)
            // last write wins within the same request
            result[key] = entity
        }
        return result
    }

    private fun updateEntityFromDto(entity: Word, dto: WordDto, tagById: Map<Long, Tag>): Word {
        entity.originalWord = dto.originalWord
        entity.translation = dto.translation
        entity.description = dto.description
        entity.sourceLanguage = dto.sourceLanguage
        entity.targetLanguage = dto.targetLanguage
        entity.level = dto.level
        entity.easeFactor = dto.easeFactor
        entity.interval = dto.interval
        entity.repetitions = dto.repetitions
        entity.lastReviewDate = dto.lastReviewDate
        entity.nextReviewDate = dto.nextReviewDate
        // Only update tags when the client explicitly provides them; empty list means "no tag info"
        if (dto.tagIds.isNotEmpty()) {
            entity.tags = dto.tagIds.mapNotNull { tagById[it] }.toMutableSet()
        }
        return entity
    }

    private fun createEntityFromDto(user: User, dto: WordDto, tagById: Map<Long, Tag>): Word = Word(
        id = dto.id,
        user = user,
        originalWord = dto.originalWord,
        translation = dto.translation,
        description = dto.description,
        sourceLanguage = dto.sourceLanguage,
        targetLanguage = dto.targetLanguage,
        level = dto.level,
        easeFactor = dto.easeFactor,
        interval = dto.interval,
        repetitions = dto.repetitions,
        lastReviewDate = dto.lastReviewDate,
        nextReviewDate = dto.nextReviewDate,
        tags = dto.tagIds.mapNotNull { tagById[it] }.toMutableSet(),
    )

    private data class WordKey(val originalWord: String, val translation: String)
}



