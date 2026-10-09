package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class WordUpsertPreparerTest {

    private lateinit var wordRepository: WordRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var wordUpsertPreparer: WordUpsertPreparer

    @BeforeEach
    fun setUp() {
        wordRepository = mockk()
        tagRepository = mockk()
        wordUpsertPreparer = WordUpsertPreparer(wordRepository, tagRepository)
    }

    @Test
    fun `prepareUpsertEntities should return empty when words list is empty`() {
        val user = createUser()

        val result = wordUpsertPreparer.prepareUpsertEntities(user, emptyList())

        assertTrue(result.isEmpty())
        verify(exactly = 0) { wordRepository.findAllByUser(any()) }
        verify(exactly = 0) { tagRepository.findAllByUserAndIdIn(any(), any()) }
    }

    @Test
    fun `prepareUpsertEntities should create new word when key does not exist`() {
        val user = createUser()
        val dto = createWordDto(originalWord = "apple", translation = "Apfel")
        every { wordRepository.findAllByUser(user) } returns emptyList()

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        assertEquals(1, result.size)
        val entity = result.single()
        assertNull(entity.id)
        assertEquals("apple", entity.originalWord)
        assertEquals("Apfel", entity.translation)
        assertEquals(user, entity.user)
    }

    @Test
    fun `prepareUpsertEntities should update existing word when key matches`() {
        val user = createUser()
        val existing = createWord(id = 42L, user = user, originalWord = "apple", translation = "Apfel", level = 0)
        val dto = createWordDto(originalWord = "apple", translation = "Apfel", level = 3, description = "a fruit")
        every { wordRepository.findAllByUser(user) } returns listOf(existing)

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        assertEquals(1, result.size)
        val entity = result.single()
        assertEquals(42L, entity.id)
        assertEquals(3, entity.level)
        assertEquals("a fruit", entity.description)
    }

    @Test
    fun `prepareUpsertEntities matches existing words case-insensitively`() {
        val user = createUser()
        val existing = createWord(id = 42L, user = user, originalWord = "Apple", translation = "Apfel", level = 0)
        every { wordRepository.findAllByUser(user) } returns listOf(existing)

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(createWordDto(originalWord = "apple ", translation = "APFEL")))

        assertEquals(42L, result.single().id)
    }

    @Test
    fun `prepareUpsertEntities treats the same word in another learning language as new`() {
        val user = createUser()
        val existing = createWord(id = 42L, user = user, originalWord = "bank", translation = "bank")
        every { wordRepository.findAllByUser(user) } returns listOf(existing)

        val result = wordUpsertPreparer.prepareUpsertEntities(
            user,
            listOf(createWordDto(originalWord = "bank", translation = "bank", targetLanguage = "nl")),
        )

        assertNull(result.single().id)
        assertEquals("nl", result.single().targetLanguage)
    }

    @Test
    fun `prepareUpsertEntities with ids the user does not own matches by content instead of duplicating`() {
        val user = createUser()
        val existing = createWord(id = 42L, user = user, originalWord = "apple", translation = "Apfel")
        // A device-local id that is not this user's server id.
        val dto = createWordDto(id = 7L, originalWord = "apple", translation = "Apfel", level = 2)
        every { wordRepository.findAllByUserAndIdIn(user, listOf(7L)) } returns emptyList()
        every { wordRepository.findAllByUser(user) } returns listOf(existing)

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        assertEquals(42L, result.single().id)
        assertEquals(2, result.single().level)
    }

    @Test
    fun `prepareUpsertEntities should handle duplicate keys by using last write`() {
        val user = createUser()
        val dto1 = createWordDto(originalWord = "apple", translation = "Apfel", level = 1)
        val dto2 = createWordDto(originalWord = "apple", translation = "Apfel", level = 5)
        every { wordRepository.findAllByUser(user) } returns emptyList()

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto1, dto2))

        assertEquals(1, result.size)
        assertEquals(5, result.single().level)
    }

    @Test
    fun `prepareUpsertEntities should load tags when tagIds are present`() {
        val user = createUser()
        val tag = createTag(id = 10L, user = user, name = "verb")
        val dto = createWordDto(originalWord = "run", translation = "laufen", tagIds = listOf(10L))
        every { wordRepository.findAllByUser(user) } returns emptyList()
        every { tagRepository.findAllByUserAndIdIn(user, listOf(10L)) } returns listOf(tag)

        wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        verify(exactly = 1) { tagRepository.findAllByUserAndIdIn(user, listOf(10L)) }
    }

    @Test
    fun `prepareUpsertEntities should not load tags when all words have empty tagIds`() {
        val user = createUser()
        val dto = createWordDto(originalWord = "run", translation = "laufen", tagIds = emptyList())
        every { wordRepository.findAllByUser(user) } returns emptyList()

        wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        verify(exactly = 0) { tagRepository.findAllByUserAndIdIn(any(), any()) }
    }

    @Test
    fun `prepareUpsertEntities should assign tags to entity from tagById map`() {
        val user = createUser()
        val tag1 = createTag(id = 10L, user = user, name = "verb")
        val tag2 = createTag(id = 11L, user = user, name = "noun")
        val dto = createWordDto(originalWord = "run", translation = "laufen", tagIds = listOf(10L, 11L))
        every { wordRepository.findAllByUser(user) } returns emptyList()
        every { tagRepository.findAllByUserAndIdIn(user, match { it.containsAll(listOf(10L, 11L)) }) } returns listOf(tag1, tag2)

        val result = wordUpsertPreparer.prepareUpsertEntities(user, listOf(dto))

        val entity = result.single()
        assertEquals(2, entity.tags.size)
        assertTrue(entity.tags.contains(tag1))
        assertTrue(entity.tags.contains(tag2))
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        firstWordAddedAt = null,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

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
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
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
}
