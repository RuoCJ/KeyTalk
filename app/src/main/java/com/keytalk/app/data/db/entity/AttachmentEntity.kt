package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
        Index("sha256"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val type: String,
    val localEncryptedUri: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val sha256: String,
    val createdAtMillis: Long,
)
