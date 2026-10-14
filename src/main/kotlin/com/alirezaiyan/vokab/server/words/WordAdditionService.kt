package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * A word to add as a brand-new card. Languages are codes; tags are names, created when missing.
 * Without [nativeLanguage] the learner's usual translation language is used.
 */
data class NewWord(
    val term: String,
    val translation: String,
    val note: String,
    val learningLanguage: String,
    val nativeLanguage: String?,
    val tagNames: List<String>,
)

/** What [WordAdditionService.addNew] stored, and the terms it left alone because they already exist. */
data class AddWordsResult(val added: List<WordDto>, val skipped: List<String>)

/**
 * Adding words from outside the app (AI clients over MCP). Unlike [WordService.upsert], which syncs
 * a device's full copy of each word, this never touches an existing word: a word the learner already
 * has keeps its spaced-repetition progress, and only genuinely new words become cards.
 */
@Service
class WordAdditionService(
    private val wordRepository: WordRepository,
    private val tagRepository: TagRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    /** Adds [words] as fresh cards, due now, skipping those the learner already has (same term, translation, language). */
    @Transactional
    fun addNew(userId: Long, words: List<NewWord>): AddWordsResult {
        if (words.isEmpty()) return AddWordsResult(emptyList(), emptyList())
        val user = userRepository.getReferenceById(userId)
        val existing = wordRepository.findAllByUser(user)
        val seen = existing.mapTo(mutableSetOf()) { termKey(it.originalWord, it.translation, it.targetLanguage) }
        val usualNativeLanguage = existing.groupingBy { it.sourceLanguage }.eachCount().maxByOrNull { it.value }?.key
            ?: DEFAULT_NATIVE_LANGUAGE
        val (fresh, skipped) = words.partition { seen.add(termKey(it.term, it.translation, it.learningLanguage)) }

        val tags = resolveTags(user, fresh.flatMap { it.tagNames })
        val now = Instant.now(clock)
        val saved = wordRepository.saveAll(
            fresh.map { word ->
                Word(
                    user = user,
                    originalWord = word.term.trim(),
                    translation = word.translation.trim(),
                    description = word.note.trim(),
                    sourceLanguage = word.nativeLanguage ?: usualNativeLanguage,
                    targetLanguage = word.learningLanguage,
                    nextReviewDate = now.toEpochMilli(),
                    createdAt = now,
                    updatedAt = now,
                    tags = word.tagNames.mapNotNullTo(mutableSetOf()) { tags[it.trim().lowercase()] },
                )
            }
        )
        if (saved.isNotEmpty() && user.firstWordAddedAt == null) {
            user.firstWordAddedAt = now
            userRepository.save(user)
        }
        return AddWordsResult(saved.map { it.toDto() }, skipped.map { it.term })
    }

    /**
     * Adds tags (by name, created when missing) to the learner's words among [wordIds], keeping the
     * tags they already have. Ids of other learners' words are ignored. Returns how many words changed.
     */
    @Transactional
    fun addTags(userId: Long, wordIds: List<Long>, tagNames: List<String>): Int {
        if (wordIds.isEmpty() || tagNames.isEmpty()) return 0
        val user = userRepository.getReferenceById(userId)
        val words = wordRepository.findAllByUserAndIdIn(user, wordIds)
        if (words.isEmpty()) return 0
        val tags = resolveTags(user, tagNames).values
        val now = Instant.now(clock)
        words.forEach { word ->
            word.tags.addAll(tags)
            word.updatedAt = now
        }
        wordRepository.saveAll(words)
        return words.size
    }

    /** The user's tags for [names], matched case-insensitively, keyed by lower-cased name; missing ones are created. */
    private fun resolveTags(user: User, names: List<String>): Map<String, Tag> {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (wanted.isEmpty()) return emptyMap()
        val existing = tagRepository.findAllByUser(user).associateBy { it.name.lowercase() }
        return wanted.associate { name ->
            name.lowercase() to (existing[name.lowercase()] ?: tagRepository.save(Tag(user = user, name = name)))
        }
    }

    /** Same identity as the app's add flow: term and translation in the same learning language. */
    private fun termKey(term: String, translation: String, learningLanguage: String) =
        Triple(term.trim().lowercase(), translation.trim().lowercase(), learningLanguage.trim().lowercase())

    private companion object {
        const val DEFAULT_NATIVE_LANGUAGE = "en"
    }
}
