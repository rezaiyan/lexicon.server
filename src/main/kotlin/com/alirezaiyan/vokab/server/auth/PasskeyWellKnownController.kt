package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.AppProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The files that let the Lexicon apps use passkeys for the RP ID. Caddy routes both paths here from
 * the RP ID's host, so the trusted apps and the accepted origins ([com.alirezaiyan.vokab.server.shared.PasskeyConfig.originSet])
 * come from the same config.
 */
@RestController
@RequestMapping("/.well-known")
class PasskeyWellKnownController(
    private val appProperties: AppProperties,
) {
    private val config get() = appProperties.passkey

    /** Android: the app package and signing keys allowed to use passkeys (Digital Asset Links). */
    @GetMapping("/assetlinks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun assetLinks(): List<Map<String, Any>> = listOf(
        mapOf(
            "relation" to listOf("delegate_permission/common.get_login_creds"),
            "target" to mapOf(
                "namespace" to "android_app",
                "package_name" to config.androidPackage,
                "sha256_cert_fingerprints" to config.androidFingerprintList,
            ),
        ),
    )

    /** iOS: the apps whose `webcredentials:` associated domain is the RP ID. */
    @GetMapping("/apple-app-site-association", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun appleAppSiteAssociation(): Map<String, Any> =
        mapOf("webcredentials" to mapOf("apps" to config.iosAppIdList))
}
