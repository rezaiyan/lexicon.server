package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.AppConfig
import com.alirezaiyan.vokab.server.domain.entity.AppConfigHistory
import com.alirezaiyan.vokab.server.domain.repository.AppConfigHistoryRepository
import com.alirezaiyan.vokab.server.domain.repository.AppConfigRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val CACHE_TTL_SECONDS = 30L

@Service
class AppConfigService(
    private val appConfigRepository: AppConfigRepository,
    private val appConfigHistoryRepository: AppConfigHistoryRepository,
    private val clock: Clock
) {
    private data class CacheEntry(val value: String?, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Returns the current value for the given key, or null if not found / disabled.
     * Results are cached for [CACHE_TTL_SECONDS] seconds so callers on hot paths
     * (e.g. JWT filter) don't hit the DB on every request.
     */
    @Transactional(readOnly = true)
    fun get(namespace: String, key: String): String? {
        val cacheKey = "$namespace:$key"
        val entry = cache[cacheKey]
        if (entry != null && entry.expiresAt.isAfter(Instant.now(clock))) {
            return entry.value
        }
        val value = appConfigRepository.findByNamespaceAndKeyAndEnabledTrue(namespace, key)?.value
        cache[cacheKey] = CacheEntry(value, Instant.now(clock).plusSeconds(CACHE_TTL_SECONDS))
        return value
    }

    @Transactional(readOnly = true)
    fun find(namespace: String, key: String): AppConfig? =
        appConfigRepository.findByNamespaceAndKey(namespace, key)

    @Transactional(readOnly = true)
    fun list(): List<AppConfig> = appConfigRepository.findAllByOrderByNamespaceAscKeyAsc()

    @Transactional(readOnly = true)
    fun history(namespace: String, key: String): List<AppConfigHistory> =
        appConfigHistoryRepository.findByNamespaceAndKeyOrderByChangedAtDesc(namespace, key)

    @Transactional
    fun set(namespace: String, key: String, value: String, changedBy: String?): AppConfig {
        val config = appConfigRepository.findByNamespaceAndKey(namespace, key)
            ?: throw NoSuchElementException("Config not found: $namespace/$key")
        val oldValue = config.value
        config.value = value
        config.updatedAt = Instant.now(clock)
        val updated = appConfigRepository.save(config)
        appConfigHistoryRepository.save(
            AppConfigHistory(
                namespace = namespace,
                key = key,
                oldValue = oldValue,
                newValue = value,
                changedBy = changedBy
            )
        )
        cache.remove("$namespace:$key")
        logger.info { "Config updated: $namespace/$key by ${changedBy ?: "unknown"}" }
        return updated
    }

    @Transactional
    fun addListItem(namespace: String, key: String, item: String, changedBy: String?): AppConfig {
        val config = appConfigRepository.findByNamespaceAndKey(namespace, key)
            ?: throw NoSuchElementException("Config not found: $namespace/$key")
        val existing = config.value?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        require(item !in existing) { "Item already exists: $item" }
        return set(namespace, key, (existing + item).joinToString(","), changedBy)
    }

    @Transactional
    fun removeListItem(namespace: String, key: String, item: String, changedBy: String?): AppConfig {
        val config = appConfigRepository.findByNamespaceAndKey(namespace, key)
            ?: throw NoSuchElementException("Config not found: $namespace/$key")
        val existing = config.value?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        require(item in existing) { "Item not found: $item" }
        return set(namespace, key, existing.filter { it != item }.joinToString(","), changedBy)
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    fun getTestEmails(): Set<String> =
        get("testing", "test_emails")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}
