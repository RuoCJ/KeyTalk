package com.keytalk.app.domain.model

import java.time.Instant
import java.util.UUID

enum class AttachmentType {
    IMAGE,
}

data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val type: AttachmentType = AttachmentType.IMAGE,
    val localEncryptedUri: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val sha256: String,
    val createdAt: Instant,
) {
    init {
        require(messageId.isNotBlank()) { "消息标识不能为空。" }
        require(localEncryptedUri.isNotBlank()) { "本地加密图片地址不能为空。" }
        require(mimeType.isNotBlank()) { "图片 MIME 类型不能为空。" }
        require(width > 0) { "图片宽度必须大于 0。" }
        require(height > 0) { "图片高度必须大于 0。" }
        require(sizeBytes > 0) { "图片大小必须大于 0。" }
        require(sha256.isNotBlank()) { "图片 SHA-256 摘要不能为空。" }
    }
}
