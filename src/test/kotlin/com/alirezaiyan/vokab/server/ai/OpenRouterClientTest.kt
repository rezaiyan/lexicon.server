package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.OpenRouterConfig
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException

class OpenRouterClientTest {

    private val chatUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val mapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = client(builder, "anthropic/claude-haiku-4.5")

    @Test
    fun `requests post the configured model with auth and attribution headers`() {
        val custom = RestClient.builder()
        val customServer = MockRestServiceServer.bindTo(custom).build()
        val customClient = client(custom, "anthropic/custom-model")
        customServer.expect(requestTo(chatUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
            .andExpect(header("X-Title", "Vokab"))
            .andExpect(jsonPath("$.model").value("anthropic/custom-model"))
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
            .andExpect(jsonPath("$.messages[0].content[0].text").value("Translate"))
            .andRespond(answer("Hallo"))

        assertEquals("Hallo", customClient.complete("Translate", AiOperation.TRANSLATION))
        customServer.verify()
    }

    @Test
    fun `an image is sent as a JPEG data URL before the prompt`() {
        expectChat()
            .andExpect(jsonPath("$.messages[0].content[0].type").value("image_url"))
            .andExpect(jsonPath("$.messages[0].content[0].image_url.url").value("data:image/jpeg;base64,QUJD"))
            .andExpect(jsonPath("$.messages[0].content[1].text").value("Extract"))
            .andRespond(answer("Hallo,hello"))

        assertEquals("Hallo,hello", client.completeWithImage("QUJD", "Extract", AiOperation.IMAGE_EXTRACTION))
        server.verify()
    }

    @Test
    fun `complete returns the trimmed answer and counts it under the operation tag`() {
        expectChat().andRespond(answer("  {\"action\":\"send\"}  \n"))

        assertEquals("{\"action\":\"send\"}", client.complete("prompt", AiOperation.NOTIFICATION_ADVICE))
        assertEquals(
            1.0,
            meterRegistry.counter("ai.requests", "operation", "notification advice", "outcome", "success").count(),
        )
    }

    @Test
    fun `complete returns null for a blank or missing answer`() {
        expectChat().andRespond(answer("   "))
        expectChat().andRespond(json("""{"choices":[{"message":{"content":null}}]}"""))
        expectChat().andRespond(json("""{"choices":[]}"""))

        repeat(3) { assertNull(client.complete("prompt", AiOperation.TRANSLATION)) }
        assertEquals(3.0, meterRegistry.counter("ai.requests", "operation", "translation", "outcome", "empty").count())
    }

    @Test
    fun `an HTTP error status becomes UpstreamServiceException`() {
        expectChat().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThrows<UpstreamServiceException> { client.complete("prompt", AiOperation.TRANSLATION) }
        assertEquals(1.0, meterRegistry.counter("ai.requests", "operation", "translation", "outcome", "error").count())
    }

    @Test
    fun `a network failure becomes UpstreamServiceException`() {
        expectChat().andRespond(withException(IOException("read timed out")))

        assertThrows<UpstreamServiceException> { client.complete("prompt", AiOperation.TRANSLATION) }
    }

    @Test
    fun `an API error field becomes UpstreamServiceException`() {
        expectChat().andRespond(json("""{"error":{"message":"model overloaded"}}"""))

        assertThrows<UpstreamServiceException> { client.complete("prompt", AiOperation.TRANSLATION) }
    }

    @Test
    fun `default model is the configured Haiku model`() {
        assertEquals("anthropic/claude-haiku-4.5", OpenRouterConfig().model)
    }

    private fun client(builder: RestClient.Builder, model: String) = OpenRouterClient(
        builder,
        AppProperties(openrouter = OpenRouterConfig(apiKey = "test-api-key", baseUrl = "https://openrouter.ai/api/v1", model = model)),
        meterRegistry,
        AiCallTracker(fixedClock()),
    )

    private fun expectChat() = server.expect(requestTo(chatUrl))

    private fun answer(text: String) =
        json(mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text))))))

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)
}
