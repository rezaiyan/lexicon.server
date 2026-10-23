package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a token's user may still call the API: the account exists and is active, and the token
 * was issued no earlier than the user's revocation cutoff (logout-all). Cached briefly so
 * authenticated requests don't query the users table; the deactivation, deletion and logout-all
 * paths [evict] so those take effect immediately on this instance.
 */
@Component
class UserAccessCache(
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    private data class Entry(val active: Boolean, val tokensValidAfter: Instant?, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** The account exists and is active (no access token involved, e.g. MCP sign-in). */
    fun isAllowed(userId: Long): Boolean = entry(userId).active

    /**
     * An access token issued at [issuedAt] (`iat`) may still be used: the account is active and the
     * token isn't older than the user's revocation cutoff. A token without `iat` fails once a cutoff
     * exists. Both `iat` and the cutoff have whole-second precision.
     */
    fun isTokenAllowed(userId: Long, issuedAt: Instant?): Boolean {
        val entry = entry(userId)
        if (!entry.active) return false
        val cutoff = entry.tokensValidAfter ?: return true
        return issuedAt != null && !issuedAt.isBefore(cutoff)
    }

    private fun entry(userId: Long): Entry {
        val now = Instant.now(clock)
        entries[userId]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it }

        val access = userRepository.findAccessById(userId)
        val entry = Entry(
            active = access?.active == true,
            tokensValidAfter = access?.tokensValidAfter,
            expiresAt = now.plusSeconds(TTL_SECONDS),
        )
        if (entries.size >= MAX_ENTRIES) entries.values.removeIf { !it.expiresAt.isAfter(now) }
        entries[userId] = entry
        return entry
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
