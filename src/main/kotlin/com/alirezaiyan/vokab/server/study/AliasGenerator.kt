package com.alirezaiyan.vokab.server.study

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Fixed, app-specific salt so the alias is not a plain hash of the id. Changing it renames every default alias. */
private const val ALIAS_SALT = "lexicon-leaderboard-alias-v1:"

/**
 * Default leaderboard name for users without a chosen alias, e.g. "SwiftOwl0427".
 *
 * Recomputed on every request, so it must be stable per user; it is derived from a SHA-256 of the
 * salted user id so that neither the words nor the digits reveal the id.
 */
@Component
class AliasGenerator {

    private val adjectives = listOf(
        "Swift", "Bright", "Bold", "Keen", "Calm",
        "Quick", "Sharp", "Wise", "Eager", "Brave",
        "Steady", "Clever", "Noble", "Vivid", "Lucky"
    )

    private val nouns = listOf(
        "Learner", "Owl", "Fox", "Scholar", "Voyager",
        "Falcon", "Phoenix", "Pioneer", "Sage", "Spark",
        "Rover", "Crest", "Petal", "Star", "Wave"
    )

    fun generate(userId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$ALIAS_SALT$userId".toByteArray())
        val bytes = ByteBuffer.wrap(digest)
        val adjective = adjectives[Math.floorMod(bytes.getInt(0), adjectives.size)]
        val noun = nouns[Math.floorMod(bytes.getInt(4), nouns.size)]
        val suffix = Math.floorMod(bytes.getInt(8), 10000).toString().padStart(4, '0')
        return "$adjective$noun$suffix"
    }
}
