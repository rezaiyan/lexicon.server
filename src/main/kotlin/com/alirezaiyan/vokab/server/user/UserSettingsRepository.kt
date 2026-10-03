package com.alirezaiyan.vokab.server.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserSettingsRepository : JpaRepository<UserSettings, Long> {
    fun findByUser(user: User): UserSettings?
    fun findByUserId(userId: Long): UserSettings?

    @Query("SELECT s FROM UserSettings s JOIN FETCH s.user WHERE s.notificationsEnabled = true")
    fun findAllWithNotificationsEnabled(): List<UserSettings>
}


