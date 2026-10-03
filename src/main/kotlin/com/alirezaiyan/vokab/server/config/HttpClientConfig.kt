package com.alirezaiyan.vokab.server.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Scope
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/**
 * Outbound HTTP builders, so no external call can hang a request thread.
 *
 * Both builders go through Boot's [RestClientBuilderConfigurer] (context Jackson, customizers) and
 * are prototype-scoped like Boot's own: builders are mutable, so every injection point gets a
 * fresh one and a client's `baseUrl` can't leak into another.
 *
 * Clients must not set their own request factory: tests bind `MockRestServiceServer` to the
 * builder they pass in, and a later `requestFactory(...)` call would bypass it.
 */
@Configuration
class HttpClientConfig {

    /** Store, identity and messaging APIs. */
    @Bean
    @Primary
    @Scope("prototype")
    fun restClientBuilder(configurer: RestClientBuilderConfigurer): RestClient.Builder =
        configurer.configure(RestClient.builder())
            .requestFactory(requestFactory(read = DEFAULT_READ_TIMEOUT))

    /** LLM calls routinely take tens of seconds; read timeout is `app.openrouter.timeout-seconds`. */
    @Bean(AI_REST_CLIENT_BUILDER)
    @Scope("prototype")
    fun aiRestClientBuilder(configurer: RestClientBuilderConfigurer, appProperties: AppProperties): RestClient.Builder =
        configurer.configure(RestClient.builder())
            .requestFactory(requestFactory(read = Duration.ofSeconds(appProperties.openrouter.timeoutSeconds)))

    private fun requestFactory(read: Duration) =
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
            .apply { setReadTimeout(read) }

    companion object {
        const val AI_REST_CLIENT_BUILDER = "aiRestClientBuilder"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}

/**
 * A failed outbound call, safe to log: the status code or failure type only. Never log the
 * exception itself — I/O errors embed the request URL, and some URLs carry secrets (Telegram's
 * bot token is part of the path).
 */
fun RestClientException.describe(): String = when (this) {
    is RestClientResponseException -> "status=${statusCode.value()}"
    else -> javaClass.simpleName + (cause?.let { " (${it.javaClass.simpleName})" } ?: "")
}

/** Injects [HttpClientConfig.aiRestClientBuilder] (long read timeout) instead of the default builder. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier(HttpClientConfig.AI_REST_CLIENT_BUILDER)
annotation class AiRestClient
