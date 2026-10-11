package com.alirezaiyan.vokab.server.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserSettingsRepository : JpaRepository<UserSettings, Long> {
    fun findByUser(user: User): UserSettings?
    fun findByUserId(userId: Long): UserSettings?

    @Query("SELECT s FROM UserSettings s JOIN FETCH s.user WHERE s.notificationsEnabled = true")
    fun findAllWithNotificationsEnabled(): List<UserSettings>

    /** Users who switched notifications off; manual admin pushes skip them by default. */
    @Query(
        "SELECT s.user.id FROM UserSettings s " +
            "WHERE s.notificationsEnabled = false OR s.notificationFrequency = 'OFF'"
    )
    fun findNotificationOptedOutUserIds(): Set<Long>

    /** Users a push could reach (active account, active token) who switched notifications off. */
    @Query(
        "SELECT COUNT(DISTINCT s.user.id) FROM UserSettings s " +
            "WHERE (s.notificationsEnabled = false OR s.notificationFrequency = 'OFF') " +
            "AND s.user.active = true " +
            "AND EXISTS (SELECT p FROM PushToken p WHERE p.user = s.user AND p.active = true)"
    )
    fun countReachableOptedOutUsers(): Long
}
