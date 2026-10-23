package com.alirezaiyan.vokab.server.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** RFC 7591 client registration request; only the fields we act on. */
data class ClientRegistrationRequest(
    @JsonProperty("redirect_uris") val redirectUris: List<String> = emptyList(),
    @JsonProperty("client_name") val clientName: String? = null,
    @JsonProperty("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
    @JsonProperty("grant_types") val grantTypes: List<String>? = null,
    @JsonProperty("response_types") val responseTypes: List<String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClientRegistrationResponse(
    @JsonProperty("client_id") val clientId: String,
    @JsonProperty("client_id_issued_at") val clientIdIssuedAt: Long,
    @JsonProperty("client_secret") val clientSecret: String?,
    @JsonProperty("client_secret_expires_at") val clientSecretExpiresAt: Long?,
    @JsonProperty("client_name") val clientName: String,
    @JsonProperty("redirect_uris") val redirectUris: List<String>,
    @JsonProperty("grant_types") val grantTypes: List<String>,
    @JsonProperty("response_types") val responseTypes: List<String>,
    @JsonProperty("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    @JsonProperty("scope") val scope: String,
)

/** RFC 7591 / RFC 6749 error body. */
data class OAuthErrorResponse(
    @JsonProperty("error") val error: String,
    @JsonProperty("error_description") val description: String,
)

data class McpSignInRequest(@JsonProperty("idToken") val idToken: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpSignInResponse(val redirectUrl: String? = null, val error: String? = null)
