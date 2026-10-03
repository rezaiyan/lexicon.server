package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.Tag
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.TagRepository
import com.alirezaiyan.vokab.server.presentation.dto.TagDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class TagService(
    private val tagRepository: TagRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(user: User): List<TagDto> {
        return tagRepository.findAllWithWordCountByUser(user).map { row ->
            val tag = row[0] as Tag
            val count = (row[1] as Number).toLong()
            tag.toDto(wordCount = count)
        }
    }

    @Transactional
    fun create(user: User, name: String): TagDto {
        val trimmed = name.trim()
        require(!tagRepository.existsByUserAndName(user, trimmed)) {
            "A tag named '$trimmed' already exists"
        }
        val tag = tagRepository.save(Tag(user = user, name = trimmed))
        logger.info { "Created tag '${tag.name}' for user ${user.id}" }
        return tag.toDto(wordCount = 0)
    }

    @Transactional
    fun rename(user: User, id: Long, name: String): TagDto {
        val trimmed = name.trim()
        val tag = tagRepository.findByIdAndUser(id, user)
            ?: throw NoSuchElementException("Tag not found")

        if (tag.name == trimmed) {
            return tag.toDto(wordCount = tagRepository.countWordsByTagId(id))
        }

        require(!tagRepository.existsByUserAndName(user, trimmed)) {
            "A tag named '$trimmed' already exists"
        }
        tag.name = trimmed
        tag.updatedAt = Instant.now(clock)
        tagRepository.save(tag)
        return tag.toDto(wordCount = tagRepository.countWordsByTagId(id))
    }

    @Transactional
    fun delete(user: User, id: Long) {
        val tag = tagRepository.findByIdAndUser(id, user)
            ?: throw NoSuchElementException("Tag not found")
        tagRepository.delete(tag)
        logger.info { "Deleted tag ${tag.id} for user ${user.id}" }
    }
}

private fun Tag.toDto(wordCount: Long): TagDto = TagDto(
    id = checkNotNull(id) { "Tag has no id; it was never persisted" },
    name = name,
    wordCount = wordCount,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)
