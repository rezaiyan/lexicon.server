package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.admin.AppConfigService
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a token's user may still call the API: the account exists and is active (test accounts
 * pass while inactive). Cached briefly so authenticated requests don't query the users table; the
 * deactivation and deletion paths [evict] so those take effect immediately.
 */
@Component
class UserAccessCache(
    private val userRepository: UserRepository,
    private val appConfigService: AppConfigService,
    private val clock: Clock,
) {
    private data class Entry(val allowed: Boolean, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun isAllowed(userId: Long): Boolean {
        val now = Instant.now(clock)
        entries[userId]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.allowed }

        val access = userRepository.findAccessById(userId)
        val allowed = access != null && (access.active || access.email in appConfigService.getTestEmails())
        if (entries.size >= MAX_ENTRIES) entries.values.removeIf { !it.expiresAt.isAfter(now) }
        entries[userId] = Entry(allowed, now.plusSeconds(TTL_SECONDS))
        return allowed
    }

    /**
     * Drops the cached answer now and, inside a transaction, again after commit, so a request that
     * re-reads the row before the change commits can't keep the stale answer for a full TTL.
     */
    fun evict(userId: Long) {
        entries.remove(userId)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    entries.remove(userId)
                }
            })
        }
    }

    companion object {
        const val TTL_SECONDS = 60L
        private const val MAX_ENTRIES = 10_000
    }
}
