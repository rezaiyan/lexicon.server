package com.alirezaiyan.vokab.server.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/** Email normalization against the migrated PostgreSQL test schema, including the V55 backfill. */
@DataJpaTest
@ActiveProfiles("test")
class UserEmailPersistenceTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `findByEmail finds a user whatever case and padding the address arrives in`() {
        val saved = userRepository.save(User(email = "reader@example.com", name = "Reader"))

        val found = userRepository.findByEmail("  Reader@EXAMPLE.com ")

        assertEquals(saved.id, found.orElse(null)?.id)
    }

    @Test
    fun `the V55 migration lowercases and trims stored emails`() {
        // Raw SQL: rows written before normalization, bypassing the entity
        jdbc.update("INSERT INTO users (email, name) VALUES (' Mixed@Example.com', 'Test')")

        jdbc.execute(v54Script())

        assertEquals(1, countEmail("mixed@example.com"))
    }

    @Test
    fun `the V55 migration refuses to run while two accounts differ only in email case`() {
        jdbc.update("INSERT INTO users (email, name) VALUES ('twin@example.com', 'One')")
        jdbc.update("INSERT INTO users (email, name) VALUES ('Twin@Example.com', 'Two')")

        val error = assertThrows<DataAccessException> { jdbc.execute(v54Script()) }

        assertTrue(error.mostSpecificCause.message.orEmpty().contains("merge those accounts first"))
    }

    private fun countEmail(email: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Int::class.java, email)!!

    private fun v54Script(): String =
        ClassPathResource("db/migration/V55__normalize_user_emails.sql").inputStream.use { String(it.readAllBytes()) }
}
