package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettings
import jakarta.persistence.EntityManagerFactory
import org.hibernate.Hibernate
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository

/**
 * The notification dispatchers run from @Scheduled without a transaction and read the schedule's user
 * (name, streak, ...) after the query returns. These queries must fetch the user with the schedule —
 * a lazy proxy would throw LazyInitializationException there. Deliberately not @Transactional, to run
 * exactly like the scheduler does.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationScheduleRepositoryFetchTest {

    @Autowired lateinit var scheduleRepository: NotificationScheduleRepository
    @Autowired lateinit var settingsRepository: UserSettingsRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val hour = 7
    private lateinit var user: User

    private fun seed(settings: UserSettings.() -> Unit) {
        user = userRepository.save(User(email = "fetch-${System.nanoTime()}@example.com", name = "Fetch User"))
        settingsRepository.save(UserSettings(user = user).apply(settings))
        scheduleRepository.save(NotificationSchedule(user = user, optimalSendHour = hour))
    }

    @AfterEach
    fun cleanUp() {
        scheduleRepository.findByUserId(requireNotNull(user.id))?.let(scheduleRepository::delete)
        settingsRepository.findByUserId(requireNotNull(user.id))?.let(settingsRepository::delete)
        userRepository.delete(user)
    }

    @Test
    fun `smart notification candidates come with their user loaded`() {
        seed { notificationsEnabled = true; notificationFrequency = "DAILY" }

        val schedule = scheduleRepository.findUsersToNotifyAtHour(hour).single { it.user.id == user.id }

        assertTrue(Hibernate.isInitialized(schedule.user))
        assertEquals("Fetch User", schedule.user.name)
    }

    @Test
    fun `loading candidates and reading their users is a single statement`() {
        seed { notificationsEnabled = true; notificationFrequency = "DAILY" }
        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        stats.clear()

        scheduleRepository.findUsersToNotifyAtHour(hour).forEach { it.user.name }

        assertEquals(1, stats.prepareStatementCount, "one query per dispatch, no per-user load (N+1)")
    }

    @Test
    fun `review reminder candidates come with their user loaded`() {
        seed { notificationsEnabled = false; reviewRemindersEnabled = true }

        val schedule = scheduleRepository.findUsersForReviewReminders(hour).single { it.user.id == user.id }

        assertTrue(Hibernate.isInitialized(schedule.user))
        assertEquals("Fetch User", schedule.user.name)
    }
}
