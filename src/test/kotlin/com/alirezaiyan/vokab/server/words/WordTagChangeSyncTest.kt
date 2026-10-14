package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Clients pull words incrementally (`GET /words?updatedAfter=`), so every change to a word's tags must
 * move its `updated_at`. Otherwise a tag assigned elsewhere (another device, an AI client) never arrives.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WordTagChangeSyncTest {

    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var wordUpsertPreparer: WordUpsertPreparer
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var entityManager: EntityManager

    private lateinit var user: User
    private val clock = MutableClock()
    private lateinit var wordService: WordService
    private lateinit var tagService: TagService

    @BeforeAll
    fun setupUser() {
        user = testUserHelper.saveAndCommit(User(email = "tag-sync@test.com", name = "Tag Sync"))
    }

    @AfterAll
    fun teardownUser() {
        testUserHelper.deleteByEmail("tag-sync@test.com")
    }

    @BeforeEach
    fun setUp() {
        clock.set(TEST_NOW)
        wordService = WordService(wordRepository, tagRepository, wordUpsertPreparer, userRepository, clock)
        tagService = TagService(tagRepository, userRepository, clock)
    }

    @Test
    fun `batchAssignTags moves the words into the next incremental pull`() {
        val tagged = saveWord("Apfel")
        saveWord("Birne")
        val tag = tagService.create(user.requireId(), "fruit")
        clock.advance(Duration.ofHours(1))

        wordService.batchAssignTags(user.requireId(), listOf(tagged), listOf(tag.id))

        assertEquals(listOf("Apfel"), changedSince())
    }

    @Test
    fun `updateWordTags moves the word into the next incremental pull`() {
        val tagged = saveWord("Apfel")
        saveWord("Birne")
        val tag = tagService.create(user.requireId(), "fruit")
        clock.advance(Duration.ofHours(1))

        wordService.updateWordTags(user.requireId(), tagged, listOf(tag.id))

        assertEquals(listOf("Apfel"), changedSince())
    }

    @Test
    fun `deleting a tag moves its words into the next incremental pull`() {
        val tagged = saveWord("Apfel")
        saveWord("Birne")
        val tag = tagService.create(user.requireId(), "fruit")
        wordService.updateWordTags(user.requireId(), tagged, listOf(tag.id))
        clock.advance(Duration.ofHours(1))
        // As in production, the delete runs without the tagged Word already loaded
        entityManager.flush()
        entityManager.clear()

        tagService.delete(user.requireId(), tag.id)

        assertEquals(listOf("Apfel"), changedSince())
    }

    private fun changedSince(): List<String> =
        wordService.list(user.requireId(), TEST_NOW.plusSeconds(1)).map { it.originalWord }.sorted()

    private fun saveWord(original: String): Long = requireNotNull(
        wordRepository.save(
            Word(
                user = user,
                originalWord = original,
                translation = original.lowercase(),
                sourceLanguage = "de",
                targetLanguage = "en",
                createdAt = TEST_NOW,
                updatedAt = TEST_NOW,
            )
        ).id
    )
}
