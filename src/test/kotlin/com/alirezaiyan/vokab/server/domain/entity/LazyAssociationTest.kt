package com.alirezaiyan.vokab.server.domain.entity

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRepository
import jakarta.persistence.EntityManager
import org.hibernate.Hibernate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Entities are open (allOpen), so Hibernate can proxy them and LAZY to-one associations stay unloaded.
 * When entities were final, every LAZY to-one was silently fetched eagerly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LazyAssociationTest {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var em: EntityManager

    @Test
    fun `a LAZY to-one association is a proxy until it is used`() {
        val user = userRepository.save(User(email = "lazy-${System.nanoTime()}@example.com", name = "Lazy"))
        val word = wordRepository.save(Word(user = user, originalWord = "Hund", translation = "dog"))
        em.flush()
        em.clear()

        val loaded = wordRepository.findById(requireNotNull(word.id)).orElseThrow()

        assertFalse(Hibernate.isInitialized(loaded.user))
        // Reading the id must not load the user row
        assertEquals(user.id, loaded.user?.id)
        assertFalse(Hibernate.isInitialized(loaded.user))
    }
}
