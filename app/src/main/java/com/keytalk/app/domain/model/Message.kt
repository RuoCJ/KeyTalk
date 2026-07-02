package com.keytalk.app.domain.model

import com.keytalk.app.config.AppConfig
import java.time.Instant
import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus = MessageStatus.COMPLETED,
    val tokenEstimate: Int = estimateTokens(content),
    val providerInputTokens: Int? = null,
    val providerOutputTokens: Int? = null,
    val providerTotalTokens: Int? = null,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(conversationId.isNotBlank()) { "会话标识不能为空。" }
    }
}

fun estimateTokens(content: String): Int =
    if (content.isBlank()) {
        0
    } else {
        (content.length / AppConfig.Context.tokenEstimateCharsPerToken).coerceAtLeast(1)
    }
