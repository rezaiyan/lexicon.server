package com.alirezaiyan.vokab.server.subscription

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Hardening switches for the RevenueCat integration. Shares the `app.revenuecat` prefix with the
 * credentials in `AppProperties.revenuecat`; each class binds only its own keys.
 *
 * Env vars (relaxed binding): `APP_REVENUECAT_ALLOWSANDBOX`, `APP_REVENUECAT_REQUIREWEBHOOKSIGNATURE`.
 */
@ConfigurationProperties(prefix = "app.revenuecat")
data class RevenueCatPolicyProperties(
    /**
     * Honour sandbox (test-store) purchases from webhooks and the store sync. Off by default, so a
     * free sandbox purchase can't unlock premium in production; turn on for dev/staging.
     */
    var allowSandbox: Boolean = false,
    /**
     * Reject every webhook while `app.revenuecat.webhook-signing-secret` is unset. Off by default
     * (only a loud startup warning) so existing deployments without a signing secret keep working.
     */
    var requireWebhookSignature: Boolean = false,
)
