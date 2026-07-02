package com.keytalk.app.provider.gemini

import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.provider.ChatError
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.ProviderAdapter
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.SseEvent
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.provider.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class GeminiNativeAdapter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : ProviderAdapter {
    override fun buildRequest(request: ChatRequest): Request {
        val endpointUrl = runCatching { contentUrl(request.baseUrl, request.model, request.stream).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Gemini Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !request.allowInsecureHttp) {
            throw ProviderException(
                ChatError(ChatErrorType.INVALID_BASE_URL, "Gemini Native 默认只允许 HTTPS Base URL。"),
            )
        }

        val payload = buildJsonObject {
            request.systemPrompt()?.let { system ->
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put("parts", buildJsonArray { add(textPart(system)) })
                    },
                )
            }
            put("contents", request.messages.toGeminiContents())
            val generationConfig = buildJsonObject {
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("maxOutputTokens", it) }
                request.reasoningEffort.toGeminiThinkingBudget()?.let { budget ->
                    put(
                        "thinkingConfig",
                        buildJsonObject { put("thinkingBudget", budget) },
                    )
                }
            }
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
        }

        return Request.Builder()
            .url(endpointUrl)
            .header("x-goog-api-key", request.apiKey)
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(AppConfig.Network.jsonContentType.toMediaType()))
            .build()
    }

    override fun parseNonStreamingResponse(requestId: String, body: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("Gemini 响应 JSON 无效。") }
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        return ChatResponse(
            requestId = requestId,
            messageText = candidate.extractText(),
            finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull,
            usage = parseUsage(root["usageMetadata"]?.jsonObject),
        )
    }

    override fun parseStreamingEvent(requestId: String, event: SseEvent): StreamEvent? {
        val data = event.data.trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") return StreamEvent.MessageCompleted(requestId, "stop")

        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return StreamEvent.Error(
                requestId,
                ChatError(ChatErrorType.INVALID_RESPONSE, "无法解析 Gemini 流式响应。"),
            )
        root["error"]?.jsonObject?.let { return StreamEvent.Error(requestId, mapGeminiError(it)) }
        val usage = parseUsage(root["usageMetadata"]?.jsonObject)
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        val text = candidate.extractText()
        return when {
            !text.isNullOrEmpty() -> StreamEvent.DeltaText(requestId, text)
            finishReason != null -> StreamEvent.MessageCompleted(requestId, finishReason, usage)
            usage != null -> StreamEvent.UsageReported(requestId, usage)
            else -> null
        }
    }

    override fun mapHttpError(statusCode: Int, body: String?): ChatError {
        val sanitized = sanitizeRawResponse(body)
        val providerStatus = parseProviderStatus(body)
        val (type, message, retryable) = when {
            providerStatus == "UNAUTHENTICATED" || statusCode == 401 ->
                Triple(ChatErrorType.INVALID_API_KEY, "Gemini API Key 无效或未授权。", false)
            providerStatus == "PERMISSION_DENIED" || statusCode == 403 ->
                Triple(ChatErrorType.PERMISSION_DENIED, "当前 Google 账号或 API Key 无权访问该模型。", false)
            providerStatus == "NOT_FOUND" || statusCode == 404 ->
                Triple(ChatErrorType.MODEL_NOT_FOUND, "Gemini 模型名或接口地址不存在。", false)
            providerStatus == "RESOURCE_EXHAUSTED" || statusCode == 429 ->
                Triple(ChatErrorType.RATE_LIMITED, "Gemini 请求过于频繁或额度不足。", true)
            statusCode == 400 ->
                Triple(ChatErrorType.INVALID_RESPONSE, "Gemini 请求参数无效，请检查模型配置。", false)
            statusCode == 413 ->
                Triple(ChatErrorType.CONTEXT_EXCEEDED, "Gemini 请求内容过大或上下文超限。", false)
            statusCode in 500..599 ->
                Triple(ChatErrorType.PROVIDER_UNAVAILABLE, "Gemini 服务暂时不可用。", true)
            else ->
                Triple(ChatErrorType.UNKNOWN, "Gemini 返回未知错误。", false)
        }
        return ChatError(type, message, retryable, statusCode, sanitized)
    }

    fun contentUrl(baseUrl: String, model: String, stream: Boolean): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val versioned = if (trimmed.endsWith("/v1") || trimmed.endsWith("/v1beta")) trimmed else "$trimmed/v1beta"
        val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$versioned/models/${model.urlEncoded()}:$method"
    }

    private fun ChatRequest.systemPrompt(): String? =
        messages.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }

    private fun List<ChatMessage>.toGeminiContents(): JsonArray = buildJsonArray {
        filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            add(
                buildJsonObject {
                    put("role", if (message.role == MessageRole.ASSISTANT) "model" else "user")
                    put(
                        "parts",
                        buildJsonArray {
                            if (message.content.isNotBlank()) add(textPart(message.content))
                            message.images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", image.mediaType)
                                                put("data", image.base64Data)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject { put("text", text) }

    private fun JsonObject?.extractText(): String =
        this?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            .orEmpty()

    private fun parseUsage(usageObject: JsonObject?): Usage? {
        usageObject ?: return null
        return Usage(
            inputTokens = usageObject["promptTokenCount"]?.jsonPrimitive?.intOrNull,
            outputTokens = usageObject["candidatesTokenCount"]?.jsonPrimitive?.intOrNull,
            totalTokens = usageObject["totalTokenCount"]?.jsonPrimitive?.intOrNull,
            providerReported = true,
        )
    }

    private fun mapGeminiError(error: JsonObject): ChatError {
        val status = error["status"]?.jsonPrimitive?.contentOrNull
        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Gemini 返回错误。"
        val type = when (status) {
            "UNAUTHENTICATED" -> ChatErrorType.INVALID_API_KEY
            "PERMISSION_DENIED" -> ChatErrorType.PERMISSION_DENIED
            "NOT_FOUND" -> ChatErrorType.MODEL_NOT_FOUND
            "RESOURCE_EXHAUSTED" -> ChatErrorType.RATE_LIMITED
            else -> ChatErrorType.UNKNOWN
        }
        return ChatError(type, message, retryable = type == ChatErrorType.RATE_LIMITED)
    }

    private fun parseProviderStatus(body: String?): String? =
        runCatching {
            body ?: return null
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("status")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun sanitizeRawResponse(body: String?): String? =
        body
            ?.replace(Regex("(?i)(x-goog-api-key|authorization|key)\\s*[:=]\\s*[^\\s,}&]+"), "\$1: <redacted>")
            ?.take(AppConfig.Network.providerRawResponseMaxChars)

    private fun com.keytalk.app.domain.model.ModelReasoningEffort?.toGeminiThinkingBudget(): Int? =
        when (this) {
            null -> null
            com.keytalk.app.domain.model.ModelReasoningEffort.NONE -> 0
            com.keytalk.app.domain.model.ModelReasoningEffort.MINIMAL -> 1_024
            com.keytalk.app.domain.model.ModelReasoningEffort.LOW -> 4_096
            com.keytalk.app.domain.model.ModelReasoningEffort.MEDIUM -> 8_192
            com.keytalk.app.domain.model.ModelReasoningEffort.HIGH -> 16_384
            com.keytalk.app.domain.model.ModelReasoningEffort.XHIGH -> 24_576
            com.keytalk.app.domain.model.ModelReasoningEffort.MAX -> 32_768
        }
}
