package com.keytalk.app.domain.model

import com.keytalk.app.config.AppConfig
import java.time.Instant
import java.util.UUID

enum class ModelReasoningEffort(
    val wireName: String,
    val displayName: String,
) {
    NONE("none", "不启用"),
    MINIMAL("minimal", "极低"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "超高"),
    MAX("max", "最大");

    companion object {
        fun fromStorage(value: String?): ModelReasoningEffort? =
            entries.firstOrNull { effort ->
                effort.name == value || effort.wireName == value
            }
    }
}

data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val displayName: String,
    val model: String,
    val modelSource: String = AppConfig.Provider.defaultModelSourceName,
    val supportsStreaming: Boolean = true,
    val supportsVision: Boolean = false,
    val defaultContextWindow: Int = AppConfig.Context.defaultContextWindow,
    val supports1mContext: Boolean = false,
    val enable1mContext: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ModelReasoningEffort? = null,
    val isDefault: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(connectionId.isNotBlank()) { "连接标识不能为空。" }
        require(displayName.isNotBlank()) { "模型显示名称不能为空。" }
        require(model.isNotBlank()) { "模型标识不能为空。" }
        require(defaultContextWindow > 0) { "上下文窗口必须大于 0。" }
        require(!enable1mContext || supports1mContext) {
            "启用 1M 上下文前，模型需被识别或填写为支持 1M 级上下文。"
        }
    }
}
