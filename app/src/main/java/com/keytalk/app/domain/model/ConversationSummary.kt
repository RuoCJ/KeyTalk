package com.keytalk.app.domain.model

import java.time.Instant
import java.util.UUID

data class ConversationSummary(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val summaryContent: String,
    val coveredMessageStartId: String?,
    val coveredMessageEndId: String?,
    val tokenEstimate: Int = estimateTokens(summaryContent),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(conversationId.isNotBlank()) { "会话标识不能为空。" }
        require(summaryContent.isNotBlank()) { "摘要内容不能为空。" }
        require(tokenEstimate > 0) { "摘要 Token 估算值必须大于 0。" }
    }
}
