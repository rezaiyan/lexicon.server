package com.alirezaiyan.vokab.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    var jwt: JwtConfig = JwtConfig(),
    var firebase: FirebaseAppConfig = FirebaseAppConfig(),
    var openrouter: OpenRouterConfig = OpenRouterConfig(),
    var apple: AppleConfig = AppleConfig(),
    var revenuecat: RevenueCatConfig = RevenueCatConfig(),
    var cors: CorsConfig = CorsConfig(),
    var features: FeatureFlagsConfig = FeatureFlagsConfig(),
    var security: SecurityConfig = SecurityConfig(),
    var logging: LoggingConfig = LoggingConfig(),
    var vocabulary: VocabularyConfig = VocabularyConfig(),
    var ciAuth: CiAuthConfig = CiAuthConfig(),
    var notifications: NotificationsConfig = NotificationsConfig(),
    var geolocation: GeoLocationConfig = GeoLocationConfig()
)

data class JwtConfig(
    var expirationMs: Long = 86400000,
    var refreshExpirationMs: Long = 7_776_000_000, // 90 days
    var refreshTokenGracePeriodMs: Long = 30_000, // 30 seconds grace period for rotated tokens
    var privateKeyPath: String = "",
    var publicKeyPath: String = "",
    var privateKey: String = "", // Base64-encoded RSA private key (for ephemeral deployments)
    var publicKey: String = "",  // Base64-encoded RSA public key (for ephemeral deployments)
    var issuer: String = "vokab-server",
    var audience: String = "vokab-client"
)

data class FirebaseAppConfig(
    var serviceAccountPath: String = ""
)

data class OpenRouterConfig(
    var apiKey: String = "",
    var baseUrl: String = "https://openrouter.ai/api/v1",
    var model: String = "anthropic/claude-haiku-4.5",
    var timeoutSeconds: Long = 60
)

data class AppleConfig(
    /** Bundle / Services IDs accepted as the `aud` of Apple ID tokens (comma-separated). */
    var clientIds: String = "com.alirezaiyan.vokab"
) {
    val clientIdSet: Set<String>
        get() = clientIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

data class RevenueCatConfig(
    /** Static `Authorization` header value configured on the RevenueCat webhook. Required. */
    var webhookSecret: String = "",
    /** HMAC signing secret; when set, every delivery must carry a valid `X-RevenueCat-Webhook-Signature`. */
    var webhookSigningSecret: String = "",
    /** Max age/skew of the signature timestamp (`t=`), in seconds. */
    var webhookSignatureToleranceSeconds: Long = 300,
    var apiKey: String = ""
)

data class CorsConfig(
    var allowedOrigins: String = "http://localhost:3000"
)

data class FeatureFlagsConfig(
    var pushNotificationsEnabled: Boolean = true
)

data class SecurityConfig(
    var testEmails: String = "",  // Comma-separated list of test emails that bypass active check
    var adminApiKey: String = ""  // Static key for X-Admin-Key header (CLI access to /admin/**)
)

data class LoggingConfig(
    var enabled: Boolean = true,
    var maxBodySize: Int = 50000,
    var excludePatterns: String = "/actuator/health,/h2-console/**",
    var logLevel: String = "INFO"
)

/**
 * Vocabulary-related configuration.
 *
 * The suggestionCount value controls how many vocabulary items the AI
 * should generate for onboarding and suggest-vocabulary flows.
 * It can be overridden via environment variable:
 *
 *   APP_VOCABULARY_SUGGESTIONCOUNT=150
 */
data class VocabularyConfig(
    var suggestionCount: Int = 50
)

data class CiAuthConfig(
    var enabled: Boolean = false,
    var secret: String = "",
    var testEmail: String = "ci-maestro@test.vokab.dev"
)

data class NotificationsConfig(
    var admin: AdminConfig = AdminConfig()
) {
    data class AdminConfig(
        var enabled: Boolean = true,
        var telegram: TelegramConfig = TelegramConfig()
    )
}

data class TelegramConfig(
    var botToken: String = "",
    var chatId: String = ""
)

data class GeoLocationConfig(
    var databasePath: String = ""
)
