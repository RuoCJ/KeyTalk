package com.keytalk.app.provider.claude

import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ProtocolAdapter
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

class ClaudeNativeAdapterTest {
    private val adapter = ClaudeNativeAdapter()

    @Test
    fun buildRequestCreatesClaudeMessagesPayloadAndHeaders() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.CLAUDE_NATIVE,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-ant",
                model = "claude-sonnet-4",
                messages = listOf(
                    ChatMessage(MessageRole.SYSTEM, "be concise"),
                    ChatMessage(MessageRole.USER, "hello"),
                ),
                stream = false,
                maxTokens = 64,
                enable1mContext = true,
            ),
        )

        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("sk-ant", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("context-1m-2025-08-07", request.header("anthropic-beta"))
    }

    @Test
    fun parseNonStreamingResponseExtractsTextAndUsage() {
        val response = adapter.parseNonStreamingResponse(
            "req-1",
            """
                {
                  "content":[{"type":"text","text":"hello"}],
                  "stop_reason":"end_turn",
                  "usage":{"input_tokens":3,"output_tokens":4}
                }
            """.trimIndent(),
        )

        assertEquals("hello", response.messageText)
        assertEquals("end_turn", response.finishReason)
        assertEquals(7, response.usage?.totalTokens)
    }

    @Test
    fun buildRequestUsesClaudeImageContentBlocks() {
        val request = adapter.buildRequest(
            ChatRequest(
                requestId = "req-1",
                protocolAdapter = ProtocolAdapter.CLAUDE_NATIVE,
                baseUrl = "https://api.anthropic.com",
                apiKey = "sk-ant",
                model = "claude-sonnet-4",
                messages = listOf(
                    ChatMessage(
                        role = MessageRole.USER,
                        content = "describe",
                        images = listOf(ChatImage("image/jpeg", "aGVsbG8=")),
                    ),
                ),
                stream = false,
            ),
        )

        val body = request.bodyString()

        assertTrue(body.contains("\"type\":\"image\""))
        assertTrue(body.contains("\"media_type\":\"image/jpeg\""))
        assertTrue(body.contains("\"data\":\"aGVsbG8=\""))
    }

    @Test
    fun parseStreamingDeltaAndStop() {
        val delta = adapter.parseStreamingLine(
            "req-1",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"he"}}""",
        )
        val done = adapter.parseStreamingLine("req-1", """data: {"type":"message_stop"}""")

        assertTrue(delta is StreamEvent.DeltaText)
        assertEquals("he", (delta as StreamEvent.DeltaText).textDelta)
        assertTrue(done is StreamEvent.MessageCompleted)
    }

    @Test
    fun mapsClaudeErrors() {
        assertEquals(ChatErrorType.INVALID_API_KEY, adapter.mapHttpError(401, "{}").type)
        assertEquals(
            ChatErrorType.CONTEXT_EXCEEDED,
            adapter.mapHttpError(400, """{"error":{"type":"request_too_large"}}""").type,
        )
    }

    @Test
    fun rejectsHttpByDefault() {
        try {
            adapter.buildRequest(
                ChatRequest(
                    baseUrl = "http://api.anthropic.com",
                    apiKey = "sk",
                    model = "claude",
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
