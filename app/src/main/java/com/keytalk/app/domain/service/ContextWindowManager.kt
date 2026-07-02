package com.keytalk.app.domain.service

import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.provider.ChatImage
import com.keytalk.app.provider.ChatMessage

enum class ContextPressure {
    NORMAL,
    WARNING_70,
    WARNING_85,
    CRITICAL_95,
}

data class ContextUsage(
    val usedTokens: Int,
    val limitTokens: Int,
    val ratio: Double,
    val pressure: ContextPressure,
)

data class ContextBuildResult(
    val messages: List<ChatMessage>,
    val generatedSummary: String?,
    val coveredMessageStartId: String?,
    val coveredMessageEndId: String?,
)

class ContextWindowManager(
    private val recentTargetRatio: Double = AppConfig.Context.defaultRecentTargetRatio,
) {
    fun limitTokens(model: ModelProfile): Int =
        if (model.supports1mContext && model.enable1mContext) AppConfig.Context.oneMillionWindow else model.defaultContextWindow

    fun usage(messages: List<Message>, model: ModelProfile): ContextUsage {
        val limit = limitTokens(model)
        val used = messages.sumOf { message ->
            message.providerTotalTokens ?: message.tokenEstimate
        }
        val ratio = if (limit == 0) 0.0 else used.toDouble() / limit.toDouble()
        return ContextUsage(
            usedTokens = used,
            limitTokens = limit,
            ratio = ratio,
            pressure = when {
                ratio >= AppConfig.Context.criticalRatio95 -> ContextPressure.CRITICAL_95
                ratio >= AppConfig.Context.warningRatio85 -> ContextPressure.WARNING_85
                ratio >= AppConfig.Context.warningRatio70 -> ContextPressure.WARNING_70
                else -> ContextPressure.NORMAL
            },
        )
    }

    fun buildRequestMessages(
        history: List<Message>,
        model: ModelProfile,
        imageProvider: (Message) -> List<ChatImage> = { emptyList() },
    ): List<ChatMessage> = buildRequestContext(history, model, imageProvider).messages

    fun buildRequestContext(
        history: List<Message>,
        model: ModelProfile,
        imageProvider: (Message) -> List<ChatImage> = { emptyList() },
    ): ContextBuildResult {
        val limit = limitTokens(model)
        val target = (limit * recentTargetRatio).toInt().coerceAtLeast(1)
        val total = history.sumOf { it.providerTotalTokens ?: it.tokenEstimate }
        if (total <= target) {
            return ContextBuildResult(
                messages = history.map { it.toChatMessage(imageProvider) },
                generatedSummary = null,
                coveredMessageStartId = null,
                coveredMessageEndId = null,
            )
        }

        val recent = mutableListOf<Message>()
        var running = 0
        for (message in history.asReversed()) {
            val estimate = message.providerTotalTokens ?: message.tokenEstimate
            if (running + estimate > target && recent.isNotEmpty()) break
            recent += message
            running += estimate
        }
        val recentInOrder = recent.asReversed()
        val summarized = history.take(history.size - recentInOrder.size)
        val summary = summarize(summarized)

        return ContextBuildResult(
            messages = buildList {
                if (summary.isNotBlank()) add(ChatMessage(MessageRole.SYSTEM, summary))
                addAll(recentInOrder.map { it.toChatMessage(imageProvider) })
            },
            generatedSummary = summary.ifBlank { null },
            coveredMessageStartId = summarized.firstOrNull()?.id,
            coveredMessageEndId = summarized.lastOrNull()?.id,
        )
    }

    private fun Message.toChatMessage(imageProvider: (Message) -> List<ChatImage>): ChatMessage =
        ChatMessage(role = role, content = content, images = imageProvider(this))

    private fun summarize(messages: List<Message>): String =
        messages
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { message ->
                "${message.role.name.lowercase()}: ${message.content.take(AppConfig.Context.summaryMessagePreviewChars).ifBlank { "[非文本或空内容]" }}"
            }
            ?.let { "滚动摘要（自动生成，用于压缩较早上下文）：\n$it" }
            .orEmpty()
}
