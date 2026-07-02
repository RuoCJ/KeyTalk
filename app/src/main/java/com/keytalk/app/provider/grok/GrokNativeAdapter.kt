package com.keytalk.app.provider.grok

import com.keytalk.app.provider.ChatError
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.ProviderAdapter
import com.keytalk.app.provider.SseEvent
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.provider.openai.OpenAICompatibleAdapter

class GrokNativeAdapter(
    private val delegate: OpenAICompatibleAdapter = OpenAICompatibleAdapter(),
) : ProviderAdapter {
    override fun buildRequest(request: ChatRequest) = delegate.buildRequest(request)

    override fun parseNonStreamingResponse(requestId: String, body: String): ChatResponse =
        delegate.parseNonStreamingResponse(requestId, body)

    override fun parseStreamingEvent(requestId: String, event: SseEvent): StreamEvent? =
        delegate.parseStreamingEvent(requestId, event)

    override fun mapHttpError(statusCode: Int, body: String?): ChatError {
        val base = delegate.mapHttpError(statusCode, body)
        val lowerBody = body.orEmpty().lowercase()
        val type = when {
            statusCode == 401 -> ChatErrorType.INVALID_API_KEY
            statusCode == 403 -> ChatErrorType.PERMISSION_DENIED
            statusCode == 404 -> ChatErrorType.MODEL_NOT_FOUND
            statusCode == 413 || "context" in lowerBody || "token limit" in lowerBody ->
                ChatErrorType.CONTEXT_EXCEEDED
            else -> base.type
        }
        val message = when (type) {
            ChatErrorType.INVALID_API_KEY -> "Grok / xAI API Key 无效或未授权。"
            ChatErrorType.PERMISSION_DENIED -> "当前 xAI 账号无权访问该模型。"
            ChatErrorType.MODEL_NOT_FOUND -> "Grok 模型名或接口地址不存在。"
            ChatErrorType.CONTEXT_EXCEEDED -> "Grok 请求内容过大或上下文超限。"
            ChatErrorType.RATE_LIMITED -> "Grok 请求过于频繁或额度不足。"
            ChatErrorType.PROVIDER_UNAVAILABLE -> "Grok / xAI 服务暂时不可用。"
            else -> base.message
        }
        return base.copy(type = type, message = message)
    }
}
