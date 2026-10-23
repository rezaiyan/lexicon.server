package com.alirezaiyan.vokab.server.words

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/** Longest term or translation accepted (characters). */
const val MAX_WORD_TEXT_LENGTH = 500

/** Longest description accepted (characters). */
const val MAX_WORD_DESCRIPTION_LENGTH = 5000

/** Language codes are stored in VARCHAR(10) columns. */
const val MAX_LANGUAGE_CODE_LENGTH = 10

/** Most tags one word can carry in a request. */
const val MAX_TAGS_PER_WORD = 50

data class WordDto(
    val id: Long?,
    @field:Size(max = MAX_WORD_TEXT_LENGTH)
    val originalWord: String,
    @field:Size(max = MAX_WORD_TEXT_LENGTH)
    val translation: String,
    @field:Size(max = MAX_WORD_DESCRIPTION_LENGTH)
    val description: String,
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val sourceLanguage: String,
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val targetLanguage: String,
    // SRS fields are clamped into range by SrsLimits when stored.
    val level: Int,
    val easeFactor: Float,
    val interval: Int,
    val repetitions: Int,
    val lastReviewDate: Long,
    val nextReviewDate: Long,
    @field:Size(max = MAX_TAGS_PER_WORD)
    val tagIds: List<Long> = emptyList(),
    val updatedAt: Long? = null,
)

data class UpsertWordsRequest(
    @field:NotEmpty(message = "Words list must not be empty")
    @field:Size(max = 1000, message = "Cannot upsert more than 1000 words at once")
    @field:Valid
    val words: List<WordDto>,
)

data class UpdateWordRequest(
    @field:NotBlank(message = "Original word must not be blank")
    @field:Size(max = MAX_WORD_TEXT_LENGTH)
    val originalWord: String,
    @field:NotBlank(message = "Translation must not be blank")
    @field:Size(max = MAX_WORD_TEXT_LENGTH)
    val translation: String,
    @field:Size(max = MAX_WORD_DESCRIPTION_LENGTH)
    val description: String = "",
    @field:NotBlank(message = "Source language must not be blank")
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val sourceLanguage: String,
    @field:NotBlank(message = "Target language must not be blank")
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val targetLanguage: String,
    val level: Int,
    val easeFactor: Float,
    val interval: Int,
    val repetitions: Int,
    val lastReviewDate: Long,
    val nextReviewDate: Long,
    @field:Size(max = MAX_TAGS_PER_WORD)
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
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val sourceLanguage: String? = null,
    @field:Size(max = MAX_LANGUAGE_CODE_LENGTH)
    val targetLanguage: String? = null,
)

data class BatchUpdateLanguagesResponse(
    val updatedCount: Int,
)
