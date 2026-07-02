package com.keytalk.app.domain.model

import com.keytalk.app.provider.ChatImage
import java.time.Instant
import java.util.UUID

data class PreparedImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val localEncryptedUri: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val sha256: String,
    val base64Data: String,
    val createdAt: Instant = Instant.now(),
) {
    fun toAttachment(messageId: String): Attachment =
        Attachment(
            id = id,
            messageId = messageId,
            localEncryptedUri = localEncryptedUri,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            createdAt = createdAt,
        )

    fun toChatImage(): ChatImage = ChatImage(mimeType, base64Data)
}
