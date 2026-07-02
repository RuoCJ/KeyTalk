package com.keytalk.app.provider.openai

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class OpenAICompatibleAdapter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : ProviderAdapter {
    override fun buildRequest(request: ChatRequest): Request {
        val endpointUrl = runCatching { chatCompletionsUrl(request.baseUrl).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !request.allowInsecureHttp) {
            throw ProviderException(
                ChatError(
                    type = ChatErrorType.INVALID_BASE_URL,
                    message = "默认只允许 HTTPS Base URL。",
                ),
            )
        }

        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", request.stream)
            put("messages", request.messages.toOpenAiMessages())
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            request.reasoningEffort
                ?.takeUnless { it == com.keytalk.app.domain.model.ModelReasoningEffort.NONE }
                ?.let { put("reasoning_effort", it.wireName) }
        }

        return Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Accept", if (request.stream) "text/event-stream" else "application/json")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(AppConfig.Network.jsonContentType.toMediaType()))
            .build()
    }

    override fun parseNonStreamingResponse(requestId: String, body: String): ChatResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                throw IllegalArgumentException("服务商响应 JSON 无效。")
            }
        val firstChoice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val content = firstChoice
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: ""
        val finishReason = firstChoice
            ?.get("finish_reason")
            ?.jsonPrimitive
            ?.contentOrNull
        return ChatResponse(
            requestId = requestId,
            messageText = content,
            finishReason = finishReason,
            usage = parseUsage(root["usage"]?.jsonObject),
            rawResponseRef = null,
        )
    }

    override fun parseStreamingEvent(requestId: String, event: SseEvent): StreamEvent? {
        val data = event.data.trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") {
            return StreamEvent.MessageCompleted(requestId = requestId, finishReason = "stop")
        }

        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return StreamEvent.Error(
                requestId = requestId,
                error = ChatError(ChatErrorType.INVALID_RESPONSE, "无法解析服务商流式响应。"),
            )

        val usage = parseUsage(root["usage"]?.jsonObject)
        if (usage != null) return StreamEvent.UsageReported(requestId, usage)

        val firstChoice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val finishReason = firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (finishReason != null) {
            return StreamEvent.MessageCompleted(requestId = requestId, finishReason = finishReason)
        }

        val delta = firstChoice["delta"]
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull

        return if (delta.isNullOrEmpty()) null else StreamEvent.DeltaText(requestId, delta)
    }

    override fun mapHttpError(statusCode: Int, body: String?): ChatError {
        val sanitized = sanitizeRawResponse(body)
        val (type, message, retryable) = when (statusCode) {
            400 -> Triple(ChatErrorType.INVALID_RESPONSE, "请求参数无效，请检查模型配置。", false)
            401 -> Triple(ChatErrorType.INVALID_API_KEY, "API Key 无效或未授权。", false)
            403 -> Triple(ChatErrorType.PERMISSION_DENIED, "当前账号或 API Key 无权访问该模型。", false)
            404 -> Triple(ChatErrorType.MODEL_NOT_FOUND, "模型名或接口地址不存在。", false)
            408 -> Triple(ChatErrorType.NETWORK_TIMEOUT, "请求超时，请稍后重试。", true)
            413 -> Triple(ChatErrorType.CONTEXT_EXCEEDED, "请求内容过大或上下文超限。", false)
            429 -> Triple(ChatErrorType.RATE_LIMITED, "请求过于频繁或额度不足。", true)
            in 500..599 -> Triple(ChatErrorType.PROVIDER_UNAVAILABLE, "服务商暂时不可用。", true)
            else -> Triple(ChatErrorType.UNKNOWN, "服务商返回未知错误。", false)
        }
        return ChatError(
            type = type,
            message = message,
            retryable = retryable,
            httpStatusCode = statusCode,
            sanitizedRawResponse = sanitized,
        )
    }

    fun chatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) {
            "$trimmed/chat/completions"
        } else {
            "$trimmed/v1/chat/completions"
        }
    }

    private fun List<ChatMessage>.toOpenAiMessages(): JsonArray = buildJsonArray {
        forEach { message ->
            add(
                buildJsonObject {
                    put("role", message.role.toOpenAiRole())
                    if (message.images.isEmpty()) {
                        put("content", message.content)
                    } else {
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
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                buildJsonObject {
                                                    put("url", image.dataUri())
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun MessageRole.toOpenAiRole(): String = when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
    }

    private fun parseUsage(usageObject: JsonObject?): Usage? {
        usageObject ?: return null
        return Usage(
            inputTokens = usageObject["prompt_tokens"]?.jsonPrimitive?.intOrNull
                ?: usageObject["input_tokens"]?.jsonPrimitive?.intOrNull,
            outputTokens = usageObject["completion_tokens"]?.jsonPrimitive?.intOrNull
                ?: usageObject["output_tokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = usageObject["total_tokens"]?.jsonPrimitive?.intOrNull,
            providerReported = usageObject["provider_reported"]?.jsonPrimitive?.boolean ?: true,
        )
    }

    private fun sanitizeRawResponse(body: String?): String? =
        body
            ?.replace(Regex("(?i)authorization\\s*[:=]\\s*bearer\\s+[^\\s,}]+"), "Authorization: Bearer <redacted>")
            ?.take(AppConfig.Network.providerRawResponseMaxChars)
}
