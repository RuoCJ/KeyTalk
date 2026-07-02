package com.keytalk.app.provider.claude

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

class ClaudeNativeAdapter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : ProviderAdapter {
    override fun buildRequest(request: ChatRequest): Request {
        val endpointUrl = runCatching { messagesUrl(request.baseUrl).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Claude Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !request.allowInsecureHttp) {
            throw ProviderException(
                ChatError(ChatErrorType.INVALID_BASE_URL, "Claude Native 默认只允许 HTTPS Base URL。"),
            )
        }

        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", request.stream)
            put("max_tokens", request.maxTokens ?: AppConfig.Provider.claudeDefaultMaxTokens)
            request.temperature?.let { put("temperature", it) }
            request.systemPrompt()?.let { put("system", it) }
            put("messages", request.messages.toClaudeMessages())
            request.reasoningEffort.toClaudeThinkingBudget()?.let { budget ->
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", budget)
                    },
                )
            }
            if (request.enable1mContext) {
                put("context_management", buildJsonObject { put("type", "auto") })
            }
        }

        val builder = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", request.apiKey)
            .header("anthropic-version", AppConfig.Provider.anthropicVersion)
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(AppConfig.Network.jsonContentType.toMediaType()))
        if (request.enable1mContext) {
            builder.header("anthropic-beta", "context-1m-2025-08-07")
        }
        return builder.build()
    }

    override fun parseNonStreamingResponse(requestId: String, body: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalArgumentException("Claude 响应 JSON 无效。") }
        val text = root["content"]
            ?.jsonArray
            ?.mapNotNull { block ->
                val blockObject = block.jsonObject
                if (blockObject["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    blockObject["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            ?.joinToString("")
            .orEmpty()
        return ChatResponse(
            requestId = requestId,
            messageText = text,
            finishReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull,
            usage = parseUsage(root["usage"]?.jsonObject),
        )
    }

    override fun parseStreamingEvent(requestId: String, event: SseEvent): StreamEvent? {
        val data = event.data.trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") return StreamEvent.MessageCompleted(requestId, "stop")

        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return StreamEvent.Error(
                requestId,
                ChatError(ChatErrorType.INVALID_RESPONSE, "无法解析 Claude 流式响应。"),
            )
        return when (root["type"]?.jsonPrimitive?.contentOrNull ?: event.event) {
            "content_block_delta" -> {
                val text = root["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                if (text.isNullOrEmpty()) null else StreamEvent.DeltaText(requestId, text)
            }

            "message_delta" -> {
                val usage = parseUsage(root["usage"]?.jsonObject)
                val stopReason = root["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                when {
                    stopReason != null -> StreamEvent.MessageCompleted(requestId, stopReason, usage)
                    usage != null -> StreamEvent.UsageReported(requestId, usage)
                    else -> null
                }
            }

            "message_stop" -> StreamEvent.MessageCompleted(requestId, "stop")
            "error" -> StreamEvent.Error(requestId, mapClaudeError(root))
            else -> null
        }
    }

    override fun mapHttpError(statusCode: Int, body: String?): ChatError {
        val sanitized = sanitizeRawResponse(body)
        val typeFromBody = parseProviderErrorType(body)
        val (type, message, retryable) = when {
            typeFromBody == "authentication_error" || statusCode == 401 ->
                Triple(ChatErrorType.INVALID_API_KEY, "Claude API Key 无效或未授权。", false)
            statusCode == 403 ->
                Triple(ChatErrorType.PERMISSION_DENIED, "当前 Claude 账号无权访问该模型。", false)
            statusCode == 404 ->
                Triple(ChatErrorType.MODEL_NOT_FOUND, "Claude 模型名或接口地址不存在。", false)
            statusCode == 413 || typeFromBody == "request_too_large" ->
                Triple(ChatErrorType.CONTEXT_EXCEEDED, "Claude 请求内容过大或上下文超限。", false)
            statusCode == 429 ->
                Triple(ChatErrorType.RATE_LIMITED, "Claude 请求过于频繁或额度不足。", true)
            statusCode in 500..599 ->
                Triple(ChatErrorType.PROVIDER_UNAVAILABLE, "Claude 服务暂时不可用。", true)
            else ->
                Triple(ChatErrorType.UNKNOWN, "Claude 返回未知错误。", false)
        }
        return ChatError(type, message, retryable, statusCode, sanitized)
    }

    fun messagesUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/messages" else "$trimmed/v1/messages"
    }

    private fun ChatRequest.systemPrompt(): String? =
        messages.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }

    private fun List<ChatMessage>.toClaudeMessages(): JsonArray = buildJsonArray {
        filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            add(
                buildJsonObject {
                    put("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    put(
                        "content",
                        buildJsonArray {
                            if (message.content.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", message.content)
                                    },
                                )
                            }
                            message.images.forEach { image ->
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        put(
                                            "source",
                                            buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", image.mediaType)
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

    private fun parseUsage(usageObject: JsonObject?): Usage? {
        usageObject ?: return null
        val input = usageObject["input_tokens"]?.jsonPrimitive?.intOrNull
        val output = usageObject["output_tokens"]?.jsonPrimitive?.intOrNull
        return Usage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = listOfNotNull(input, output).takeIf { it.isNotEmpty() }?.sum(),
            providerReported = true,
        )
    }

    private fun mapClaudeError(root: JsonObject): ChatError {
        val error = root["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Claude 返回错误。"
        val type = when (error?.get("type")?.jsonPrimitive?.contentOrNull) {
            "authentication_error" -> ChatErrorType.INVALID_API_KEY
            "permission_error" -> ChatErrorType.PERMISSION_DENIED
            "not_found_error" -> ChatErrorType.MODEL_NOT_FOUND
            "rate_limit_error" -> ChatErrorType.RATE_LIMITED
            "request_too_large" -> ChatErrorType.CONTEXT_EXCEEDED
            else -> ChatErrorType.UNKNOWN
        }
        return ChatError(type, message, retryable = type == ChatErrorType.RATE_LIMITED)
    }

    private fun parseProviderErrorType(body: String?): String? =
        runCatching {
            body ?: return null
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("type")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    private fun sanitizeRawResponse(body: String?): String? =
        body
            ?.replace(Regex("(?i)(x-api-key|authorization)\\s*[:=]\\s*[^\\s,}]+"), "\$1: <redacted>")
            ?.take(AppConfig.Network.providerRawResponseMaxChars)

    private fun com.keytalk.app.domain.model.ModelReasoningEffort?.toClaudeThinkingBudget(): Int? =
        when (this) {
            null,
            com.keytalk.app.domain.model.ModelReasoningEffort.NONE,
            -> null
            com.keytalk.app.domain.model.ModelReasoningEffort.MINIMAL -> 1_024
            com.keytalk.app.domain.model.ModelReasoningEffort.LOW -> 4_096
            com.keytalk.app.domain.model.ModelReasoningEffort.MEDIUM -> 8_192
            com.keytalk.app.domain.model.ModelReasoningEffort.HIGH -> 16_384
            com.keytalk.app.domain.model.ModelReasoningEffort.XHIGH -> 32_768
            com.keytalk.app.domain.model.ModelReasoningEffort.MAX -> 65_536
        }
}
