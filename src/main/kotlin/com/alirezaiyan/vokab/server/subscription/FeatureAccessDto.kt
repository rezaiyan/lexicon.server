package com.alirezaiyan.vokab.server.subscription

/**
 * Combined response with feature flags and user's access status
 */
data class FeatureAccessResponse(
    val featureFlags: ClientFeatureFlags,
    val userAccess: UserFeatureAccess
)


