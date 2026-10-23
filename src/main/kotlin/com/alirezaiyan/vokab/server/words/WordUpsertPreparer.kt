package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
import org.springframework.stereotype.Component

@Component
class WordUpsertPreparer(
    private val wordRepository: WordRepository,
    private val tagRepository: TagRepository,
) {
    fun prepareUpsertEntities(user: User, words: List<WordDto>): Collection<Word> {
        if (words.isEmpty()) return emptyList()

        val existing = loadExisting(user, words)
        val tagById = loadTagsById(user, words)
        return buildEntitiesToSave(user, words, existing, tagById).values
    }

    /**
     * The caller's own words that the request may touch. Every query here is scoped to [user], so a
     * client-supplied id can only ever resolve to one of the caller's words.
     */
    private fun loadExisting(user: User, words: List<WordDto>): ExistingWords {
        val knownIds = words.mapNotNull { it.id }
        val byId = if (knownIds.size == words.size) wordRepository.findAllByUserAndIdIn(user, knownIds) else null
        // Full scan to match by content when some words are new, or when an id isn't this user's
        // (a device-local id): matching only by id would store a duplicate.
        val existing = byId?.takeIf { it.size == knownIds.distinct().size } ?: wordRepository.findAllByUser(user)
        return ExistingWords(
            byKey = existing.associateBy { WordKey.of(it.originalWord, it.translation, it.targetLanguage) },
            byId = existing.associateBy { checkNotNull(it.id) },
        )
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
        existing: ExistingWords,
        tagById: Map<Long, Tag>,
    ): LinkedHashMap<WordKey, Word> {
        val result = LinkedHashMap<WordKey, Word>()
        for (dto in words) {
            val key = WordKey.of(dto.originalWord, dto.translation, dto.targetLanguage)
            // Match by content first, then by id among the caller's own words (an edited term).
            val match = existing.byKey[key] ?: dto.id?.let { existing.byId[it] }
            val entity = match?.let { updateEntityFromDto(it, dto, tagById) }
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
        entity.level = SrsLimits.level(dto.level)
        entity.easeFactor = SrsLimits.easeFactor(dto.easeFactor)
        entity.interval = SrsLimits.interval(dto.interval)
        entity.repetitions = SrsLimits.repetitions(dto.repetitions)
        entity.lastReviewDate = dto.lastReviewDate
        entity.nextReviewDate = dto.nextReviewDate
        // Only update tags when the client explicitly provides them; empty list means "no tag info"
        if (dto.tagIds.isNotEmpty()) {
            entity.tags = dto.tagIds.mapNotNull { tagById[it] }.toMutableSet()
        }
        return entity
    }

    /**
     * A new row. The client's id is never reused: it is only a lookup key among the caller's own
     * words, and a non-null id would make `save` merge onto whatever row has it, another user's included.
     */
    private fun createEntityFromDto(user: User, dto: WordDto, tagById: Map<Long, Tag>): Word = Word(
        id = null,
        user = user,
        originalWord = dto.originalWord,
        translation = dto.translation,
        description = dto.description,
        sourceLanguage = dto.sourceLanguage,
        targetLanguage = dto.targetLanguage,
        level = SrsLimits.level(dto.level),
        easeFactor = SrsLimits.easeFactor(dto.easeFactor),
        interval = SrsLimits.interval(dto.interval),
        repetitions = SrsLimits.repetitions(dto.repetitions),
        lastReviewDate = dto.lastReviewDate,
        nextReviewDate = dto.nextReviewDate,
        tags = dto.tagIds.mapNotNull { tagById[it] }.toMutableSet(),
    )

    private class ExistingWords(val byKey: Map<WordKey, Word>, val byId: Map<Long, Word>)

    /**
     * Content identity of a word: same term and translation (case-insensitive) in the same learning
     * language. The same term learned in two languages is two words.
     */
    private data class WordKey(val originalWord: String, val translation: String, val targetLanguage: String) {
        companion object {
            fun of(originalWord: String, translation: String, targetLanguage: String) = WordKey(
                originalWord.trim().lowercase(),
                translation.trim().lowercase(),
                targetLanguage.trim().lowercase(),
            )
        }
    }
}



