package com.alirezaiyan.vokab.server.words

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

data class WordDto(
    val id: Long?,
    val originalWord: String,
    val translation: String,
    val description: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val level: Int,
    val easeFactor: Float,
    val interval: Int,
    val repetitions: Int,
    val lastReviewDate: Long,
    val nextReviewDate: Long,
    val tagIds: List<Long> = emptyList(),
    val updatedAt: Long? = null,
)

data class UpsertWordsRequest(
    @field:NotEmpty(message = "Words list must not be empty")
    @field:Size(max = 1000, message = "Cannot upsert more than 1000 words at once")
    val words: List<WordDto>,
)

data class UpdateWordRequest(
    @field:NotBlank(message = "Original word must not be blank")
    val originalWord: String,
    @field:NotBlank(message = "Translation must not be blank")
    val translation: String,
    val description: String = "",
    @field:NotBlank(message = "Source language must not be blank")
    val sourceLanguage: String,
    @field:NotBlank(message = "Target language must not be blank")
    val targetLanguage: String,
    val level: Int,
    val easeFactor: Float,
    val interval: Int,
    val repetitions: Int,
    val lastReviewDate: Long,
    val nextReviewDate: Long,
    val tagIds: List<Long> = emptyList(),
)

data class BatchDeleteRequest(
    @field:NotEmpty(message = "IDs list must not be empty")
    @field:Size(max = 1000, message = "Cannot delete more than 1000 words at once")
    val ids: List<Long>,
)

data class BatchDeleteResponse(
    val deletedCount: Int,
)

data class BatchUpdateLanguagesRequest(
    @field:NotEmpty(message = "IDs list must not be empty")
    @field:Size(max = 1000, message = "Cannot update more than 1000 words at once")
    val ids: List<Long>,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
)

data class BatchUpdateLanguagesResponse(
    val updatedCount: Int,
)
