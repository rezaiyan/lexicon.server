package com.alirezaiyan.vokab.server.security

/**
 * The authenticated caller, taken from the access token's subject. Controllers receive it via
 * `@AuthenticationPrincipal` and hand services the id; services load the [com.alirezaiyan.vokab.server.domain.entity.User]
 * only when they need its fields, instead of every request loading the entity up front.
 */
data class AuthUser(val id: Long)
