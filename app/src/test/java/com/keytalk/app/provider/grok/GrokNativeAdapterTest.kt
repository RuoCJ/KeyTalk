package com.keytalk.app.provider.grok

import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatImage
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.StreamEvent
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokNativeAdapterTest {
    private val adapter = GrokNativeAdapter()

    @Test
    fun buildRequestCreatesXaiChatCompletionsPayloadAndBearerAuth() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.GROK_NATIVE,
                baseUrl = "https://api.x.ai",
                apiKey = "xai-test-key",
                model = "grok-4",
                messages = listOf(ChatMessage(MessageRole.USER, "hello grok")),
                stream = true,
                temperature = 0.2,
                maxTokens = 256,
            ),
        )

        val body = request.bodyString()

        assertEquals("https://api.x.ai/v1/chat/completions", request.url.toString())
        assertEquals("Bearer xai-test-key", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertTrue(body.contains("\"model\":\"grok-4\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"temperature\":0.2"))
        assertTrue(body.contains("\"max_tokens\":256"))
    }

    @Test
    fun buildRequestUsesOpenAiCompatibleVisionPayload() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.GROK_NATIVE,
                baseUrl = "https://api.x.ai/v1",
                apiKey = "xai-test-key",
                model = "grok-vision",
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

        assertEquals("https://api.x.ai/v1/chat/completions", request.url.toString())
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,aGVsbG8="))
    }

    @Test
    fun parseResponsesAndStreamingEventsLikeOpenAiCompatibleApi() {
        val response = adapter.parseNonStreamingResponse(
            requestId = "req-1",
            body = """
                {
                  "choices": [{"message": {"content": "pong"}, "finish_reason": "stop"}],
                  "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
                }
            """.trimIndent(),
        )
        val delta = adapter.parseStreamingLine(
            requestId = "req-1",
            line = """data: {"choices":[{"delta":{"content":"he"}}]}""",
        )
        val done = adapter.parseStreamingLine("req-1", "data: [DONE]")

        assertEquals("pong", response.messageText)
        assertEquals("stop", response.finishReason)
        assertEquals(7, response.usage?.totalTokens)
        assertTrue(delta is StreamEvent.DeltaText)
        assertEquals("he", (delta as StreamEvent.DeltaText).textDelta)
        assertTrue(done is StreamEvent.MessageCompleted)
    }

    @Test
    fun mapsXaiSpecificErrorsToUnifiedTypesAndMessages() {
        assertEquals(ChatErrorType.INVALID_API_KEY, adapter.mapHttpError(401, "{}").type)
        assertEquals(ChatErrorType.PERMISSION_DENIED, adapter.mapHttpError(403, "{}").type)
        assertEquals(ChatErrorType.MODEL_NOT_FOUND, adapter.mapHttpError(404, "{}").type)
        assertEquals(ChatErrorType.CONTEXT_EXCEEDED, adapter.mapHttpError(413, "{}").type)
        assertEquals(
            ChatErrorType.CONTEXT_EXCEEDED,
            adapter.mapHttpError(400, """{"error":"token limit exceeded"}""").type,
        )

        val rateLimited = adapter.mapHttpError(429, "{}")
        assertEquals(ChatErrorType.RATE_LIMITED, rateLimited.type)
        assertTrue(rateLimited.retryable)
        assertTrue(rateLimited.message.contains("Grok"))
    }
}

private fun okhttp3.Request.bodyString(): String {
    val buffer = Buffer()
    body!!.writeTo(buffer)
    return buffer.readUtf8()
}
