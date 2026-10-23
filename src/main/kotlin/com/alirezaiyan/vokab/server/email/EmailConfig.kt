package com.alirezaiyan.vokab.server.email

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app.email")
data class EmailConfig(
    var provider: String = "log",
    var apiKey: String = "",
    var fromAddress: String = "Lexicon <noreply@lexicon.app>",
    var replyTo: String = "",
    var enabled: Boolean = false,
    var smtpHost: String = "",
    var smtpPort: Int = 465,
    var smtpUser: String = "",
    var smtpPassword: String = ""
)
