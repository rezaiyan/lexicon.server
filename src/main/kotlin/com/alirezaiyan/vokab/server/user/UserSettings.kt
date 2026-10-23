package com.alirezaiyan.vokab.server.user

import jakarta.persistence.*
import com.alirezaiyan.vokab.server.shared.JpaEntity

@Entity
@Table(name = "user_settings")
class UserSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override var id: Long? = null,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    var user: User? = null,

    @Column(nullable = false)
    var languageCode: String = "en",

    @Column(nullable = false)
    var themeMode: String = "AUTO",

    // Notifications - simplified to on/off + time
    @Column(nullable = false)
    var notificationsEnabled: Boolean = true,

    @Column(nullable = false)
    var reviewRemindersEnabled: Boolean = true,

    @Column(nullable = false)
    var dailyReminderTime: String = "18:00",

    @Column(nullable = false)
    var notificationFrequency: String = "DAILY",

    /** IANA zone id from the device; null until a client reports it. */
    @Column(length = 64)
    var timezone: String? = null
) : JpaEntity<Long>()


