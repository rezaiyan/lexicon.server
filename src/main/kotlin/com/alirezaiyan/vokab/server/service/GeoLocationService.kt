package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.config.AppProperties
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.maxmind.db.InvalidDatabaseException
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.AddressNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.net.InetAddress

private val logger = KotlinLogging.logger {}

@Service
class GeoLocationService(
    private val appProperties: AppProperties,
    private val userRepository: UserRepository,
) {

    private var reader: DatabaseReader? = null

    @PostConstruct
    fun init() {
        val path = appProperties.geolocation.databasePath
        if (path.isBlank()) {
            logger.warn { "GeoLite2 database path not configured — geo lookup disabled" }
            return
        }
        val file = File(path)
        if (!file.exists()) {
            logger.warn { "GeoLite2 database not found at '$path' — geo lookup disabled" }
            return
        }
        try {
            reader = DatabaseReader.Builder(file).build()
            logger.info { "GeoLite2 database loaded from '$path'" }
        } catch (e: InvalidDatabaseException) {
            logger.error(e) { "Failed to load GeoLite2 database from '$path'" }
        }
    }

    @PreDestroy
    fun destroy() {
        reader?.close()
    }

    /**
     * Resolves an IP address to an ISO alpha-2 country code.
     * Returns null for private/unresolvable IPs or when the DB is unavailable.
     * Never throws.
     */
    fun resolveCountry(ipAddress: String): String? {
        val dbReader = reader ?: return null
        return try {
            val ip = InetAddress.getByName(ipAddress)
            dbReader.country(ip).country.isoCode
        } catch (e: AddressNotFoundException) {
            // Private or reserved IP — expected during local dev
            null
        } catch (e: Exception) {
            logger.warn(e) { "Failed to resolve country for IP '$ipAddress'" }
            null
        }
    }

    /**
     * Resolves the country for the given IP and updates the user's country columns.
     * Runs asynchronously so it never blocks the auth response.
     * signup_country is set only once (immutable after first set).
     */
    @Async
    @Transactional
    fun updateUserCountry(userId: Long, ipAddress: String, isNewUser: Boolean) {
        val country = resolveCountry(ipAddress) ?: return
        val user = userRepository.findById(userId).orElse(null) ?: return
        user.lastLoginCountry = country
        if (isNewUser || user.signupCountry == null) user.signupCountry = country
        userRepository.save(user)
        logger.info { "Updated country for userId=$userId: country=$country (isNewUser=$isNewUser)" }
    }
}
