package com.alirezaiyan.vokab.server.shared

import com.fasterxml.jackson.annotation.JsonInclude

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    /** Machine-readable error code (see `ApiErrorCode`); omitted from the JSON when absent. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val code: String? = null,
)

data class ErrorResponse(
    val success: Boolean = false,
    val message: String,
    val details: String? = null
)
