package com.keytalk.app.provider.openai

import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatImage
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class OpenAICompatibleAdapterTest {
    private val adapter = OpenAICompatibleAdapter()

    @Test
    fun buildRequestCreatesOpenAiChatCompletionsPayload() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                baseUrl = "https://relay.example",
                apiKey = "sk-test",
                model = "demo-model",
                messages = listOf(ChatMessage(MessageRole.USER, "hello")),
                stream = false,
            ),
        )

        assertEquals("https://relay.example/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    @Test
    fun parseNonStreamingResponseExtractsMessageAndUsage() {
        val response = adapter.parseNonStreamingResponse(
            requestId = "req-1",
            body = """
                {
                  "choices": [{"message": {"content": "hi"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
                }
            """.trimIndent(),
        )

        assertEquals("hi", response.messageText)
        assertEquals("stop", response.finishReason)
        assertEquals(7, response.usage?.totalTokens)
    }

    @Test
    fun buildRequestUsesOpenAiVisionContentPartsForImages() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                baseUrl = "https://relay.example",
                apiKey = "sk-test",
                model = "vision-model",
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "describe",
                        images = listOf(ChatImage("image/png", "aGVsbG8=")),
                    ),
                ),
                stream = false,
            ),
        )

        val body = request.bodyString()

        assertTrue(body.contains("image_url"))
        assertTrue(body.contains("data:image/png;base64,aGVsbG8="))
    }

    @Test
    fun parseStreamingDeltaAndDone() {
        val delta = adapter.parseStreamingLine(
            requestId = "req-1",
            line = """data: {"choices":[{"delta":{"content":"hel"}}]}""",
        )
        val done = adapter.parseStreamingLine("req-1", "data: [DONE]")

        assertTrue(delta is StreamEvent.DeltaText)
        assertEquals("hel", (delta as StreamEvent.DeltaText).textDelta)
        assertTrue(done is StreamEvent.MessageCompleted)
    }

    @Test
    fun mapsHttpErrorsWithoutAuthorizationHeader() {
        val error = adapter.mapHttpError(401, """{"error":"bad"} Authorization: Bearer sk-secret""")

        assertEquals(ChatErrorType.INVALID_API_KEY, error.type)
        assertTrue(error.sanitizedRawResponse!!.contains("<redacted>"))
    }

    @Test
    fun mapsCommonHttpErrorsToUserFacingTypes() {
        assertEquals(ChatErrorType.PERMISSION_DENIED, adapter.mapHttpError(403, "{}").type)
        assertEquals(ChatErrorType.MODEL_NOT_FOUND, adapter.mapHttpError(404, "{}").type)
        assertEquals(ChatErrorType.RATE_LIMITED, adapter.mapHttpError(429, "{}").type)

        val serverError = adapter.mapHttpError(503, "{}")
        assertEquals(ChatErrorType.PROVIDER_UNAVAILABLE, serverError.type)
        assertTrue(serverError.retryable)

        val timeout = adapter.mapHttpError(408, "{}")
        assertEquals(ChatErrorType.NETWORK_TIMEOUT, timeout.type)
        assertTrue(timeout.retryable)
    }

    @Test
    fun rejectsHttpBaseUrlByDefault() {
        try {
            adapter.buildRequest(
                ChatRequest(
                    requestId = "req-1",
                    baseUrl = "http://relay.example",
                    apiKey = "sk-test",
                    model = "demo-model",
                    messages = listOf(ChatMessage(MessageRole.USER, "hello")),
                    stream = false,
                ),
            )
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ChatErrorType.INVALID_BASE_URL, e.error.type)
        }
    }
}

private fun okhttp3.Request.bodyString(): String {
    val buffer = Buffer()
    body!!.writeTo(buffer)
    return buffer.readUtf8()
}
