package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.user.User
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Integration test for TagRepository.
 *
 * Regression guard for the GROUP BY fix in findAllWithWordCountByUser:
 * Hibernate 6 + PostgreSQL cannot expand a bare `GROUP BY t` when the entity
 * has an inverse @ManyToMany collection.  The JPQL must enumerate every
 * non-aggregate column explicitly.  This test executes that JPQL against
 * PostgreSQL so the query is validated on every CI run.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagRepositoryTest {

    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var testUserHelper: TestUserHelper

    private lateinit var user: User
    private lateinit var otherUser: User

    // Users are committed outside the per-test transaction and removed after it rolled back:
    // deleting a user from inside a test whose open transaction references it self-deadlocks.
    @BeforeAll
    fun setupUsers() {
        user = testUserHelper.saveAndCommit(User(email = "tagrepo@test.com", name = "Tag Repo User"))
        otherUser = testUserHelper.saveAndCommit(User(email = "other@test.com", name = "Other User"))
    }

    @AfterAll
    fun teardownUsers() {
        testUserHelper.deleteByEmail("tagrepo@test.com")
        testUserHelper.deleteByEmail("other@test.com")
    }

    // Each @Test runs in a transaction that rolls back, so data never leaks between tests.

    @Test
    fun `findAllWithWordCountByUser returns empty list when user has no tags`() {
        val results = tagRepository.findAllWithWordCountByUser(user)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `findAllWithWordCountByUser returns tags with zero count when no words are assigned`() {
        tagRepository.save(Tag(user = user, name = "fruits"))
        tagRepository.save(Tag(user = user, name = "verbs"))

        val results = tagRepository.findAllWithWordCountByUser(user)

        assertEquals(2, results.size)
        val counts = results.map { it[1] as Long }
        assertTrue(counts.all { it == 0L })
    }

    @Test
    fun `findAllWithWordCountByUser returns correct word count when words are assigned`() {
        val tag = tagRepository.save(Tag(user = user, name = "animals"))

        wordRepository.save(Word(user = user, originalWord = "cat", translation = "Katze", tags = mutableSetOf(tag)))
        wordRepository.save(Word(user = user, originalWord = "dog", translation = "Hund", tags = mutableSetOf(tag)))

        val results = tagRepository.findAllWithWordCountByUser(user)

        assertEquals(1, results.size)
        val returnedTag = results[0][0] as Tag
        val count = results[0][1] as Long
        assertEquals("animals", returnedTag.name)
        assertEquals(2L, count)
    }

    @Test
    fun `findAllWithWordCountByUser only returns tags belonging to the requesting user`() {
        tagRepository.save(Tag(user = user, name = "my-tag"))
        tagRepository.save(Tag(user = otherUser, name = "their-tag"))

        val results = tagRepository.findAllWithWordCountByUser(user)

        assertEquals(1, results.size)
        val returnedTag = results[0][0] as Tag
        assertEquals("my-tag", returnedTag.name)
    }
}
