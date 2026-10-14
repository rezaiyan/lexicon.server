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
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WordAdditionServiceTest {

    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var entityManager: EntityManager

    private lateinit var user: User
    private val clock = MutableClock()
    private lateinit var service: WordAdditionService

    @BeforeAll
    fun setupUser() {
        user = testUserHelper.saveAndCommit(User(email = "word-add@test.com", name = "Word Add"))
    }

    @AfterAll
    fun teardownUser() {
        testUserHelper.deleteByEmail("word-add@test.com")
        testUserHelper.deleteByEmail("word-add-other@test.com")
    }

    @BeforeEach
    fun setUp() {
        clock.set(TEST_NOW)
        service = WordAdditionService(wordRepository, tagRepository, userRepository, clock)
    }

    @Test
    fun `addNew stores new words as fresh cards due now`() {
        val result = service.addNew(user.requireId(), listOf(newWord("Apfel", "apple")))

        assertEquals(listOf("Apfel"), result.added.map { it.originalWord })
        val stored = wordRepository.findAllByUser(user).single()
        assertEquals("de", stored.targetLanguage)
        assertEquals("en", stored.sourceLanguage)
        assertEquals(0, stored.level)
        assertEquals(TEST_NOW.toEpochMilli(), stored.nextReviewDate)
    }

    @Test
    fun `addNew skips words the learner already has and keeps their progress`() {
        wordRepository.save(Word(user = user, originalWord = "Apfel", translation = "apple", sourceLanguage = "en",
            targetLanguage = "de", level = 4, repetitions = 7, nextReviewDate = 123L))

        val result = service.addNew(user.requireId(), listOf(newWord(" apfel ", "Apple"), newWord("Birne", "pear")))

        assertEquals(listOf("Birne"), result.added.map { it.originalWord })
        assertEquals(listOf(" apfel "), result.skipped)
        val apfel = wordRepository.findAllByUser(user).single { it.originalWord == "Apfel" }
        assertEquals(4, apfel.level)
        assertEquals(7, apfel.repetitions)
        assertEquals(123L, apfel.nextReviewDate)
    }

    @Test
    fun `addNew skips duplicates within the same request`() {
        val result = service.addNew(user.requireId(), listOf(newWord("Apfel", "apple"), newWord("APFEL", "apple")))

        assertEquals(1, result.added.size)
        assertEquals(1, wordRepository.findAllByUser(user).size)
    }

    @Test
    fun `addNew tags words by name, reusing existing tags case-insensitively and creating the rest`() {
        val food = tagRepository.save(Tag(user = user, name = "Food"))

        val result = service.addNew(user.requireId(), listOf(newWord("Apfel", "apple", tags = listOf("food", "news"))))

        val tags = tagRepository.findAllByUser(user).associateBy { it.name }
        assertEquals(setOf("Food", "news"), tags.keys)
        assertEquals(setOf(food.id, tags.getValue("news").id), result.added.single().tagIds.toSet())
    }

    @Test
    fun `addTags adds to a word's tags without dropping the ones it has`() {
        val apfel = service.addNew(user.requireId(), listOf(newWord("Apfel", "apple", tags = listOf("food")))).added.single()
        entityManager.flush()
        entityManager.clear()

        service.addTags(user.requireId(), listOf(requireNotNull(apfel.id)), listOf("Food", "a1"))
        entityManager.flush()
        entityManager.clear()

        val names = wordRepository.findAllByUserWithTags(user).single().tags.map { it.name }.toSet()
        assertEquals(setOf("food", "a1"), names)
    }

    @Test
    fun `addTags ignores words of other learners`() {
        val other = testUserHelper.saveAndCommit(User(email = "word-add-other@test.com", name = "Other"))
        val theirs = wordRepository.save(Word(user = other, originalWord = "x", translation = "y",
            sourceLanguage = "en", targetLanguage = "de"))

        val tagged = service.addTags(user.requireId(), listOf(requireNotNull(theirs.id)), listOf("mine"))

        assertEquals(0, tagged)
        entityManager.flush()
        entityManager.clear()
        assertEquals(emptySet(), wordRepository.findAllByUserWithTags(other).single().tags.map { it.name }.toSet())
    }

    private fun newWord(term: String, translation: String, tags: List<String> = emptyList()) = NewWord(
        term = term,
        translation = translation,
        note = "",
        learningLanguage = "de",
        nativeLanguage = "en",
        tagNames = tags,
    )
}
