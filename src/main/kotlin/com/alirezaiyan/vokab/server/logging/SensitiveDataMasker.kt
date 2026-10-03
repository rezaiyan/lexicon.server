package com.alirezaiyan.vokab.server.logging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.*

object SensitiveDataMasker {
    
    private const val MASKED_VALUE = "***MASKED***"
    private const val TOKEN_PREFIX_LENGTH = 7
    
    private val sensitiveHeaders = setOf(
        "authorization",
        "x-api-key",
        "x-auth-token",
        "cookie",
        "set-cookie"
    )
    
    private val sensitiveJsonFields = setOf(
        "password",
        "token",
        "accessToken",
        "refreshToken",
        "idToken",
        "apiKey",
        "secret",
        "apikey",
        "authorization",
        "jwt",
        "apisecret",
        "privatekey",
        "privatekeypath"
    )
    
    fun maskHeader(name: String, value: String?): String {
        if (value == null) return ""
        
        val lowerName = name.lowercase(Locale.getDefault())
        if (!sensitiveHeaders.contains(lowerName)) {
            return value
        }
        
        return when (lowerName) {
            "authorization" -> {
                if (value.startsWith("Bearer ", ignoreCase = true)) {
                    "Bearer $MASKED_VALUE"
                } else if (value.startsWith("Basic ", ignoreCase = true)) {
                    "Basic $MASKED_VALUE"
                } else {
                    MASKED_VALUE
                }
            }
            else -> MASKED_VALUE
        }
    }
    
    fun maskHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
        return headers.mapValues { (name, values) ->
            values.map { maskHeader(name, it) }
        }
    }
    
    fun maskJsonBody(body: String): String {
        if (body.isBlank()) return body
        
        return try {
            val objectMapper = ObjectMapper()
            val jsonNode: JsonNode = objectMapper.readTree(body)
            val maskedNode = maskJsonNode(jsonNode)
            objectMapper.writeValueAsString(maskedNode)
        } catch (e: Exception) {
            body
        }
    }
    
    private fun maskJsonNode(node: JsonNode): JsonNode {
        val objectMapper = ObjectMapper()
        
        return when {
            node.isObject -> {
                val maskedObject = objectMapper.createObjectNode()
                node.properties().forEach { (fieldName, fieldValue) ->
                    val lowerFieldName = fieldName.lowercase(Locale.getDefault())
                    if (sensitiveJsonFields.any { lowerFieldName.contains(it, ignoreCase = true) }) {
                        maskedObject.put(fieldName, MASKED_VALUE)
                    } else if (fieldValue.isObject || fieldValue.isArray) {
                        maskedObject.set<JsonNode>(fieldName, maskJsonNode(fieldValue))
                    } else {
                        maskedObject.set(fieldName, fieldValue)
                    }
                }
                maskedObject
            }
            node.isArray -> {
                val maskedArray = objectMapper.createArrayNode()
                node.forEach { element ->
                    maskedArray.add(maskJsonNode(element))
                }
                maskedArray
            }
            else -> node
        }
    }
    
    fun maskQueryParams(queryString: String?): String {
        if (queryString.isNullOrBlank()) return ""
        
        val sensitiveParams = setOf("token", "api_key", "apikey", "secret", "password")
        return queryString.split("&").joinToString("&") { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                val paramName = parts[0].lowercase(Locale.getDefault())
                if (sensitiveParams.any { paramName.contains(it, ignoreCase = true) }) {
                    "${parts[0]}=$MASKED_VALUE"
                } else {
                    param
                }
            } else {
                param
            }
        }
    }
}
