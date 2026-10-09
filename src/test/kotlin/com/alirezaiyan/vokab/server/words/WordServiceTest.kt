package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Optional

class WordServiceTest {

    private lateinit var wordRepository: WordRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var wordUpsertPreparer: WordUpsertPreparer
    private lateinit var userRepository: UserRepository
    private val knownUsers = mutableListOf<User>()
    private lateinit var wordService: WordService

    @BeforeEach
    fun setUp() {
        wordRepository = mockk()
        tagRepository = mockk()
        wordUpsertPreparer = mockk()
        userRepository = mockk<UserRepository>().answerReferences(knownUsers)
        wordService = WordService(wordRepository, tagRepository, wordUpsertPreparer, userRepository, clock = fixedClock())
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list should return empty list when user has no words`() {
        val user = createUser()
        every { wordRepository.findAllByUserWithTags(user) } returns emptyList()

        val result = wordService.list(user.requireId())

        assertEquals(emptyList<WordDto>(), result)
        verify(exactly = 1) { wordRepository.findAllByUserWithTags(user) }
    }

    @Test
    fun `list should map words to DTOs`() {
        val user = createUser()
        val word = createWord(id = 10L, user = user, originalWord = "apple", translation = "Apfel")
        every { wordRepository.findAllByUserWithTags(user) } returns listOf(word)

        val result = wordService.list(user.requireId())

        assertEquals(1, result.size)
        assertEquals(10L, result[0].id)
        assertEquals("apple", result[0].originalWord)
        assertEquals("Apfel", result[0].translation)
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    fun `upsert should do nothing when words list is empty`() {
        val user = createUser()

        wordService.upsert(user.requireId(), emptyList())

        verify(exactly = 0) { wordUpsertPreparer.prepareUpsertEntities(any(), any()) }
        verify(exactly = 0) { wordRepository.saveAll(any<Collection<Word>>()) }
    }

    @Test
    fun `upsert should save entities from preparer`() {
        val user = createUser(firstWordAddedAt = TEST_NOW)
        val dto = createWordDto()
        val entity = createWord(id = 1L, user = user)
        every { wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto)) } returns listOf(entity)
        every { wordRepository.saveAll(any<Collection<Word>>()) } returns listOf(entity)

        wordService.upsert(user.requireId(), listOf(dto))

        verify(exactly = 1) { wordRepository.saveAll(any<Collection<Word>>()) }
    }

    @Test
    fun `upsert returns the saved words with their server ids`() {
        val user = createUser(firstWordAddedAt = TEST_NOW)
        val dto = createWordDto()
        val prepared = createWord(id = null, user = user)
        val saved = createWord(id = 99L, user = user)
        every { wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto)) } returns listOf(prepared)
        every { wordRepository.saveAll(any<Collection<Word>>()) } returns listOf(saved)

        val result = wordService.upsert(user.requireId(), listOf(dto))

        assertEquals(listOf(99L), result.map { it.id })
    }

    @Test
    fun `upsert should update firstWordAddedAt when new words added and firstWordAddedAt is null`() {
        val user = createUser(firstWordAddedAt = null)
        val dto = createWordDto()
        val newEntity = createWord(id = null, user = user)
        every { wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto)) } returns listOf(newEntity)
        every { wordRepository.saveAll(any<Collection<Word>>()) } returns listOf(newEntity)
        every { userRepository.save(any()) } answers { firstArg() }

        wordService.upsert(user.requireId(), listOf(dto))

        verify(exactly = 1) { userRepository.save(match { it.firstWordAddedAt != null }) }
    }

    @Test
    fun `upsert should not update firstWordAddedAt when already set`() {
        val alreadySet = Instant.parse("2024-01-01T00:00:00Z")
        val user = createUser(firstWordAddedAt = alreadySet)
        val dto = createWordDto()
        val newEntity = createWord(id = null, user = user)
        every { wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto)) } returns listOf(newEntity)
        every { wordRepository.saveAll(any<Collection<Word>>()) } returns listOf(newEntity)

        wordService.upsert(user.requireId(), listOf(dto))

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `upsert should not update firstWordAddedAt when no new words`() {
        val user = createUser(firstWordAddedAt = null)
        val dto = createWordDto()
        val existingEntity = createWord(id = 42L, user = user)
        every { wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto)) } returns listOf(existingEntity)
        every { wordRepository.saveAll(any<Collection<Word>>()) } returns listOf(existingEntity)

        wordService.upsert(user.requireId(), listOf(dto))

        verify(exactly = 0) { userRepository.save(any()) }
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update should throw NoSuchElementException when word not found`() {
        val user = createUser()
        every { wordRepository.findById(99L) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            wordService.update(user.requireId(), 99L, createUpdateWordRequest())
        }
    }

    @Test
    fun `update should throw IllegalArgumentException when word belongs to different user`() {
        val user = createUser(id = 1L)
        val otherUser = createUser(id = 2L)
        val word = createWord(id = 10L, user = otherUser)
        every { wordRepository.findById(10L) } returns Optional.of(word)

        assertThrows<IllegalArgumentException> {
            wordService.update(user.requireId(), 10L, createUpdateWordRequest())
        }
    }

    @Test
    fun `update should update word fields and save`() {
        val user = createUser(id = 1L)
        val word = createWord(id = 10L, user = user, originalWord = "old", translation = "alt")
        val request = createUpdateWordRequest(
            originalWord = "new",
            translation = "neu",
            description = "updated desc",
            level = 3,
        )
        every { wordRepository.findById(10L) } returns Optional.of(word)
        every { wordRepository.save(any()) } returns word

        wordService.update(user.requireId(), 10L, request)

        assertEquals("new", word.originalWord)
        assertEquals("neu", word.translation)
        assertEquals("updated desc", word.description)
        assertEquals(3, word.level)
        assertNotNull(word.updatedAt)
        verify(exactly = 1) { wordRepository.save(word) }
    }

    @Test
    fun `update should not update tags when tagIds is empty`() {
        val user = createUser(id = 1L)
        val word = createWord(id = 10L, user = user)
        val request = createUpdateWordRequest(tagIds = emptyList())
        every { wordRepository.findById(10L) } returns Optional.of(word)
        every { wordRepository.save(any()) } returns word

        wordService.update(user.requireId(), 10L, request)

        verify(exactly = 0) { tagRepository.findAllByUserAndIdIn(any(), any()) }
    }

    @Test
    fun `update should update tags when tagIds provided`() {
        val user = createUser(id = 1L)
        val word = createWord(id = 10L, user = user)
        val tag = createTag(id = 5L, user = user, name = "verb")
        val request = createUpdateWordRequest(tagIds = listOf(5L))
        every { wordRepository.findById(10L) } returns Optional.of(word)
        every { tagRepository.findAllByUserAndIdIn(user, listOf(5L)) } returns listOf(tag)
        every { wordRepository.save(any()) } returns word

        wordService.update(user.requireId(), 10L, request)

        assertEquals(setOf(tag), word.tags)
        verify(exactly = 1) { tagRepository.findAllByUserAndIdIn(user, listOf(5L)) }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete should throw IllegalArgumentException when word not found or belongs to different user`() {
        val user = createUser(id = 1L)
        every { wordRepository.deleteByIdAndUserId(99L, 1L) } returns 0

        assertThrows<IllegalArgumentException> {
            wordService.delete(user.requireId(), 99L)
        }
    }

    @Test
    fun `delete should delete word when valid`() {
        val user = createUser(id = 1L)
        every { wordRepository.deleteByIdAndUserId(10L, 1L) } returns 1

        wordService.delete(user.requireId(), 10L)

        verify(exactly = 1) { wordRepository.deleteByIdAndUserId(10L, 1L) }
    }

    // ── batchDelete ───────────────────────────────────────────────────────────

    @Test
    fun `batchDelete should return 0 when ids is empty`() {
        val user = createUser()

        val result = wordService.batchDelete(user.requireId(), emptyList())

        assertEquals(0, result)
        verify(exactly = 0) { wordRepository.deleteAllByIdInAndUserId(any(), any()) }
    }

    @Test
    fun `batchDelete should call repository and return count`() {
        val user = createUser(id = 1L)
        val ids = listOf(10L, 11L, 12L)
        every { wordRepository.deleteAllByIdInAndUserId(ids, 1L) } returns 3

        val result = wordService.batchDelete(user.requireId(), ids)

        assertEquals(3, result)
        verify(exactly = 1) { wordRepository.deleteAllByIdInAndUserId(ids, 1L) }
    }

    // ── batchUpdateLanguages ──────────────────────────────────────────────────

    @Test
    fun `batchUpdateLanguages should return 0 when ids is empty`() {
        val user = createUser()

        val result = wordService.batchUpdateLanguages(user.requireId(), emptyList(), "en", "de")

        assertEquals(0, result)
        verify(exactly = 0) { wordRepository.updateLanguagesByIdInAndUserId(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `batchUpdateLanguages should return 0 when both languages null`() {
        val user = createUser()
        val ids = listOf(1L, 2L)

        val result = wordService.batchUpdateLanguages(user.requireId(), ids, null, null)

        assertEquals(0, result)
        verify(exactly = 0) { wordRepository.updateLanguagesByIdInAndUserId(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { wordRepository.updateSourceLanguageByIdInAndUserId(any(), any(), any(), any()) }
        verify(exactly = 0) { wordRepository.updateTargetLanguageByIdInAndUserId(any(), any(), any(), any()) }
    }

    @Test
    fun `batchUpdateLanguages should update both languages`() {
        val user = createUser(id = 1L)
        val ids = listOf(10L, 11L)
        every {
            wordRepository.updateLanguagesByIdInAndUserId(ids, 1L, "en", "de", any())
        } returns 2

        val result = wordService.batchUpdateLanguages(user.requireId(), ids, "en", "de")

        assertEquals(2, result)
        verify(exactly = 1) { wordRepository.updateLanguagesByIdInAndUserId(ids, 1L, "en", "de", any()) }
    }

    @Test
    fun `batchUpdateLanguages should update source language only`() {
        val user = createUser(id = 1L)
        val ids = listOf(10L)
        every {
            wordRepository.updateSourceLanguageByIdInAndUserId(ids, 1L, "en", any())
        } returns 1

        val result = wordService.batchUpdateLanguages(user.requireId(), ids, "en", null)

        assertEquals(1, result)
        verify(exactly = 1) { wordRepository.updateSourceLanguageByIdInAndUserId(ids, 1L, "en", any()) }
        verify(exactly = 0) { wordRepository.updateLanguagesByIdInAndUserId(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `batchUpdateLanguages should update target language only`() {
        val user = createUser(id = 1L)
        val ids = listOf(10L)
        every {
            wordRepository.updateTargetLanguageByIdInAndUserId(ids, 1L, "de", any())
        } returns 1

        val result = wordService.batchUpdateLanguages(user.requireId(), ids, null, "de")

        assertEquals(1, result)
        verify(exactly = 1) { wordRepository.updateTargetLanguageByIdInAndUserId(ids, 1L, "de", any()) }
        verify(exactly = 0) { wordRepository.updateLanguagesByIdInAndUserId(any(), any(), any(), any(), any()) }
    }

    // ── batchAssignTags ───────────────────────────────────────────────────────

    @Test
    fun `batchAssignTags should return 0 when wordIds is empty`() {
        val user = createUser()

        val result = wordService.batchAssignTags(user.requireId(), emptyList(), listOf(1L))

        assertEquals(0, result)
        verify(exactly = 0) { wordRepository.deleteWordTagsByWordIdsAndUserId(any(), any()) }
    }

    @Test
    fun `batchAssignTags should delete existing and insert new tags in one bulk call`() {
        val user = createUser(id = 1L)
        val wordIds = listOf(10L, 11L)
        val tagIds = listOf(5L, 6L)
        justRun { wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, 1L) }
        justRun { wordRepository.insertWordTagsBulkByWordIdsAndUserId(wordIds, tagIds, 1L) }

        val result = wordService.batchAssignTags(user.requireId(), wordIds, tagIds)

        assertEquals(2, result)
        verify(exactly = 1) { wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, 1L) }
        verify(exactly = 1) { wordRepository.insertWordTagsBulkByWordIdsAndUserId(wordIds, tagIds, 1L) }
    }

    @Test
    fun `batchAssignTags should only delete when tagIds empty`() {
        val user = createUser(id = 1L)
        val wordIds = listOf(10L, 11L)
        justRun { wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, 1L) }

        val result = wordService.batchAssignTags(user.requireId(), wordIds, emptyList())

        assertEquals(2, result)
        verify(exactly = 1) { wordRepository.deleteWordTagsByWordIdsAndUserId(wordIds, 1L) }
        verify(exactly = 0) { wordRepository.insertWordTagsBulkByWordIdsAndUserId(any(), any(), any()) }
        verify(exactly = 0) { tagRepository.findAllByUserAndIdIn(any(), any()) }
    }

    // ── updateWordTags ────────────────────────────────────────────────────────

    @Test
    fun `updateWordTags should throw when word not found`() {
        val user = createUser()
        every { wordRepository.findById(99L) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            wordService.updateWordTags(user.requireId(), 99L, listOf(1L))
        }
    }

    @Test
    fun `updateWordTags should throw when word belongs to different user`() {
        val user = createUser(id = 1L)
        val otherUser = createUser(id = 2L)
        val word = createWord(id = 10L, user = otherUser)
        every { wordRepository.findById(10L) } returns Optional.of(word)

        assertThrows<IllegalArgumentException> {
            wordService.updateWordTags(user.requireId(), 10L, listOf(1L))
        }
    }

    @Test
    fun `updateWordTags should clear tags when tagIds empty`() {
        val user = createUser(id = 1L)
        val tag = createTag(id = 5L, user = user)
        val word = createWord(id = 10L, user = user, tags = mutableSetOf(tag))
        every { wordRepository.findById(10L) } returns Optional.of(word)
        every { wordRepository.save(any()) } returns word

        wordService.updateWordTags(user.requireId(), 10L, emptyList())

        assertEquals(emptySet<Tag>(), word.tags)
        verify(exactly = 1) { wordRepository.save(word) }
        verify(exactly = 0) { tagRepository.findAllByUserAndIdIn(any(), any()) }
    }

    @Test
    fun `updateWordTags should assign tags`() {
        val user = createUser(id = 1L)
        val word = createWord(id = 10L, user = user)
        val tag = createTag(id = 5L, user = user, name = "noun")
        every { wordRepository.findById(10L) } returns Optional.of(word)
        every { tagRepository.findAllByUserAndIdIn(user, listOf(5L)) } returns listOf(tag)
        every { wordRepository.save(any()) } returns word

        wordService.updateWordTags(user.requireId(), 10L, listOf(5L))

        assertEquals(setOf(tag), word.tags)
        verify(exactly = 1) { tagRepository.findAllByUserAndIdIn(user, listOf(5L)) }
        verify(exactly = 1) { wordRepository.save(word) }
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        firstWordAddedAt: Instant? = null,
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        firstWordAddedAt = firstWordAddedAt,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
    ).also { knownUsers += it }

    private fun createWord(
        id: Long? = null,
        user: User? = null,
        originalWord: String = "hello",
        translation: String = "hallo",
        description: String = "",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        level: Int = 0,
        easeFactor: Float = 2.5f,
        interval: Int = 0,
        repetitions: Int = 0,
        lastReviewDate: Long = 0L,
        nextReviewDate: Long = 0L,
        tags: MutableSet<Tag> = mutableSetOf(),
    ): Word = Word(
        id = id,
        user = user,
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
        tags = tags,
    )

    private fun createTag(
        id: Long? = null,
        user: User? = null,
        name: String = "test-tag",
    ): Tag = Tag(
        id = id,
        user = user,
        name = name,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
    )

    private fun createWordDto(
        id: Long? = null,
        originalWord: String = "hello",
        translation: String = "hallo",
        description: String = "",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        level: Int = 0,
        easeFactor: Float = 2.5f,
        interval: Int = 0,
        repetitions: Int = 0,
        lastReviewDate: Long = 0L,
        nextReviewDate: Long = 0L,
        tagIds: List<Long> = emptyList(),
    ): WordDto = WordDto(
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
        tagIds = tagIds,
    )

    private fun createUpdateWordRequest(
        originalWord: String = "hello",
        translation: String = "hallo",
        description: String = "",
        sourceLanguage: String = "en",
        targetLanguage: String = "de",
        level: Int = 0,
        easeFactor: Float = 2.5f,
        interval: Int = 0,
        repetitions: Int = 0,
        lastReviewDate: Long = 0L,
        nextReviewDate: Long = 0L,
        tagIds: List<Long> = emptyList(),
    ): UpdateWordRequest = UpdateWordRequest(
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
        tagIds = tagIds,
    )
}
