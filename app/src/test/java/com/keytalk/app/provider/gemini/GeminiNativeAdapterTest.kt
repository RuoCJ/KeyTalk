package com.keytalk.app.provider.gemini

import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatImage
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class GeminiNativeAdapterTest {
    private val adapter = GeminiNativeAdapter()

    @Test
    fun buildRequestCreatesGeminiGenerateContentPayloadAndHeaders() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "gemini-key",
                model = "gemini-2.5-pro",
                messages = listOf(ChatMessage(MessageRole.USER, "hello")),
                stream = true,
                maxTokens = 64,
            ),
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
            request.url.toString(),
        )
        assertEquals("gemini-key", request.header("x-goog-api-key"))
    }

    @Test
    fun parseNonStreamingResponseExtractsTextAndUsage() {
        val response = adapter.parseNonStreamingResponse(
            "req-1",
            """
                {
                  "candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}],
                  "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}
                }
            """.trimIndent(),
        )

        assertEquals("hello", response.messageText)
        assertEquals("STOP", response.finishReason)
        assertEquals(7, response.usage?.totalTokens)
    }

    @Test
    fun buildRequestUsesGeminiInlineDataForImages() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.GEMINI_NATIVE,
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "gemini-key",
                model = "gemini-2.5-pro",
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "describe",
                        images = listOf(ChatImage("image/webp", "aGVsbG8=")),
                    ),
                ),
                stream = false,
            ),
        )

        val body = request.bodyString()

        assertTrue(body.contains("\"inlineData\""))
        assertTrue(body.contains("\"mimeType\":\"image/webp\""))
        assertTrue(body.contains("\"data\":\"aGVsbG8=\""))
    }

    @Test
    fun parseStreamingDelta() {
        val delta = adapter.parseStreamingLine(
            "req-1",
            """data: {"candidates":[{"content":{"parts":[{"text":"he"}]}}]}""",
        )

        assertTrue(delta is StreamEvent.DeltaText)
        assertEquals("he", (delta as StreamEvent.DeltaText).textDelta)
    }

    @Test
    fun mapsGeminiErrors() {
        assertEquals(
            ChatErrorType.PERMISSION_DENIED,
            adapter.mapHttpError(403, """{"error":{"status":"PERMISSION_DENIED"}}""").type,
        )
        assertEquals(
            ChatErrorType.RATE_LIMITED,
            adapter.mapHttpError(429, """{"error":{"status":"RESOURCE_EXHAUSTED"}}""").type,
        )
    }
}

private fun okhttp3.Request.bodyString(): String {
    val buffer = Buffer()
    body!!.writeTo(buffer)
    return buffer.readUtf8()
}
