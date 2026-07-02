package com.keytalk.app.provider

import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ModelReasoningEffort
import com.keytalk.app.domain.model.ProtocolAdapter
import java.util.UUID

data class ChatRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val protocolAdapter: ProtocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null,
    val enable1mContext: Boolean = false,
    val reasoningEffort: ModelReasoningEffort? = null,
    val allowInsecureHttp: Boolean = false,
)

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val images: List<ChatImage> = emptyList(),
)

data class ChatImage(
    val mediaType: String,
    val base64Data: String,
) {
    init {
        require(mediaType in AppConfig.Provider.supportedImageMediaTypes) { "不支持的图片媒体类型：$mediaType" }
        require(base64Data.isNotBlank()) { "图片数据不能为空。" }
    }

    fun dataUri(): String = "data:$mediaType;base64,$base64Data"
}
