package com.alirezaiyan.vokab.server.user

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class UserService(
    private val userRepository: UserRepository
) {
    
    @Transactional(readOnly = true)
    fun getUserById(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }
        return user.toDto()
    }
    
    @Transactional(readOnly = true)
    fun getUserByEmail(email: String): UserDto {
        val user = userRepository.findByEmail(email)
            .orElseThrow { IllegalArgumentException("User not found") }
        return user.toDto()
    }
    
    @Transactional
    fun updateUser(userId: Long, name: String?, displayAlias: String? = null): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        if (displayAlias != null) {
            validateDisplayAlias(displayAlias)
        }

        if (name != null) user.name = name
        if (displayAlias != null) user.displayAlias = displayAlias

        val saved = userRepository.save(user)
        logger.info { "User updated: userId=${saved.id}" }

        return saved.toDto()
    }

    private fun validateDisplayAlias(alias: String) {
        val aliasRegex = "^[a-zA-Z0-9 _-]{2,30}$".toRegex()
        require(aliasRegex.matches(alias)) {
            "Username must be 2-30 characters and contain only letters, numbers, spaces, underscores, or hyphens"
        }
    }
    
    private fun User.toDto(): UserDto {
        return UserDto(
            id = requireId(),
            email = this.email,
            name = this.name,
            subscriptionStatus = this.subscriptionStatus,
            subscriptionExpiresAt = this.subscriptionExpiresAt?.toString(),
            currentStreak = this.currentStreak,
            displayAlias = this.displayAlias,
            profileImageUrl = this.profileImageUrl
        )
    }
}

