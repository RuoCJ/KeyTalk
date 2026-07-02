package com.keytalk.app.domain.model

import com.keytalk.app.config.AppConfig
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelProfileId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastMessagePreview: String = "",
    val deleteState: DeleteState = DeleteState.ACTIVE,
    val deletedAt: Instant? = null,
    val purgeAfter: Instant? = null,
) {
    init {
        require(title.isNotBlank()) { "会话标题不能为空。" }
        require(modelProfileId.isNotBlank()) { "模型配置 ID 不能为空。" }
        if (deleteState == DeleteState.ACTIVE) {
            require(deletedAt == null && purgeAfter == null) {
                "活跃会话不能包含删除时间。"
            }
        }
        if (deleteState == DeleteState.TRASH) {
            require(deletedAt != null && purgeAfter != null) {
                "回收站会话必须包含删除时间和清理时间。"
            }
        }
    }

    fun moveToTrash(now: Instant): Conversation =
        copy(
            deleteState = DeleteState.TRASH,
            deletedAt = now,
            purgeAfter = now.plus(AppConfig.Conversation.trashRetentionDays, ChronoUnit.DAYS),
            updatedAt = now,
        )

    fun restoreFromTrash(now: Instant): Conversation =
        copy(
            deleteState = DeleteState.ACTIVE,
            deletedAt = null,
            purgeAfter = null,
            updatedAt = now,
        )

    fun updatePreview(preview: String, now: Instant): Conversation =
        copy(lastMessagePreview = preview.take(AppConfig.Conversation.previewMaxChars), updatedAt = now)

    fun isTrashExpired(now: Instant): Boolean =
        deleteState == DeleteState.TRASH && purgeAfter?.let { !now.isBefore(it) } == true
}
