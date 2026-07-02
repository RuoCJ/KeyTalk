package com.keytalk.app.network

import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ProviderAdapterRegistry
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.provider.claude.ClaudeNativeAdapter
import com.keytalk.app.provider.openai.OpenAICompatibleAdapter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatNetworkClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpChatNetworkClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpChatNetworkClient(OpenAICompatibleAdapter())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsNonStreamingRequest() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"pong"},"finish_reason":"stop"}]}"""),
        )

        val response = client.send(testRequest(stream = false))
        val recorded = server.takeRequest()

        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("pong", response.messageText)
    }

    @Test
    fun parsesStreamingEvents() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"delta":{"content":"he"}}]}

                    data: {"choices":[{"delta":{"content":"llo"}}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = client.stream(testRequest(stream = true)).toList()

        assertTrue(events[0] is StreamEvent.DeltaText)
        assertEquals("he", (events[0] as StreamEvent.DeltaText).textDelta)
        assertTrue(events.last() is StreamEvent.MessageCompleted)
    }

    @Test
    fun parsesStandardSseFramesWithEventField() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message
                    data: {"choices":[{"delta":{"content":"he"}}]}

                    event: message
                    data: {"choices":[{"delta":{"content":"llo"}}]}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val events = client.stream(testRequest(stream = true)).toList()

        assertEquals(3, events.size)
        assertTrue(events[0] is StreamEvent.DeltaText)
        assertTrue(events[1] is StreamEvent.DeltaText)
        assertTrue(events[2] is StreamEvent.MessageCompleted)
    }

    @Test
    fun maps401ToInvalidApiKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        try {
            client.send(testRequest(stream = false))
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ChatErrorType.INVALID_API_KEY, e.error.type)
        }
    }

    @Test
    fun listsOpenAICompatibleModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "object": "list",
                      "data": [
                        {"id": "gpt-4o-mini", "object": "model"},
                        {"id": "deepseek-chat", "object": "model"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val models = client.listModels(
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "sk-test",
            allowInsecureHttp = true,
        )
        val recorded = server.takeRequest()

        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals(listOf("gpt-4o-mini", "deepseek-chat"), models)
    }

    @Test
    fun listsOpenAICompatibleModelsAndAppendsV1WhenMissing() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"qwen-plus"}]}"""),
        )

        val models = client.listModels(
            protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
            baseUrl = server.url("/compatible-mode").toString().trimEnd('/'),
            apiKey = "sk-test",
            allowInsecureHttp = true,
        )
        val recorded = server.takeRequest()

        assertEquals("/compatible-mode/v1/models", recorded.path)
        assertEquals(listOf("qwen-plus"), models)
    }

    @Test
    fun listsClaudeNativeModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {"id": "claude-3-5-sonnet-latest", "type": "model"},
                        {"id": "claude-3-5-haiku-latest", "type": "model"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val models = client.listModels(
            protocolAdapter = ProtocolAdapter.CLAUDE_NATIVE,
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-ant-test",
            allowInsecureHttp = true,
        )
        val recorded = server.takeRequest()

        assertEquals("/v1/models", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertTrue(recorded.getHeader("anthropic-version").orEmpty().isNotBlank())
        assertEquals(listOf("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest"), models)
    }

    @Test
    fun listsGeminiNativeModelsAndFiltersGenerateContentModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "models": [
                        {
                          "name": "models/gemini-1.5-pro",
                          "supportedGenerationMethods": ["generateContent", "countTokens"]
                        },
                        {
                          "name": "models/gemini-embedding-001",
                          "supportedGenerationMethods": ["embedContent"]
                        },
                        {
                          "name": "models/gemini-2.0-flash",
                          "supportedGenerationMethods": ["streamGenerateContent"]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val models = client.listModels(
            protocolAdapter = ProtocolAdapter.GEMINI_NATIVE,
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "gemini-test",
            allowInsecureHttp = true,
        )
        val recorded = server.takeRequest()

        assertEquals("/v1beta/models", recorded.path)
        assertEquals("gemini-test", recorded.getHeader("x-goog-api-key"))
        assertEquals(listOf("gemini-1.5-pro", "gemini-2.0-flash"), models)
    }

    @Test
    fun listModelsRejectsHttpByDefaultBeforeSendingApiKey() = runTest {
        try {
            client.listModels(
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "sk-test",
            )
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ChatErrorType.INVALID_BASE_URL, e.error.type)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun listModelsSanitizesErrorResponses() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody(
                    """
                    upstream failed
                    Authorization: Bearer sk-secret
                    api_key=sk-another
                    x-goog-api-key: google-secret
                    """.trimIndent(),
                ),
        )

        try {
            client.listModels(
                protocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "sk-test",
                allowInsecureHttp = true,
            )
            throw AssertionError("Expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ChatErrorType.INVALID_RESPONSE, e.error.type)
            val sanitized = e.error.sanitizedRawResponse.orEmpty()
            assertTrue(sanitized.contains("<redacted>"))
            assertTrue(!sanitized.contains("sk-secret"))
            assertTrue(!sanitized.contains("sk-another"))
            assertTrue(!sanitized.contains("google-secret"))
        }
    }

    @Test
    fun selectsAdapterFromRequestProtocol() = runTest {
        client = OkHttpChatNetworkClient(
            ProviderAdapterRegistry(
                mapOf(
                    ProtocolAdapter.OPENAI_COMPATIBLE to OpenAICompatibleAdapter(),
                    ProtocolAdapter.CLAUDE_NATIVE to ClaudeNativeAdapter(),
                ),
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn"}"""),
        )

        val response = client.send(testRequest(stream = false).copy(protocolAdapter = ProtocolAdapter.CLAUDE_NATIVE))
        val recorded = server.takeRequest()

        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-test", recorded.getHeader("x-api-key"))
        assertEquals("pong", response.messageText)
    }

    private fun testRequest(stream: Boolean): ChatRequest =
        ChatRequest(
            requestId = "req-1",
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "demo",
            messages = listOf(ChatMessage(MessageRole.USER, "ping")),
            stream = stream,
            allowInsecureHttp = true,
        )
}
