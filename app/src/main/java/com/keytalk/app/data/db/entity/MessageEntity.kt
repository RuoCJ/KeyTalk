package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "createdAtMillis"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val status: String,
    val tokenEstimate: Int,
    val providerInputTokens: Int?,
    val providerOutputTokens: Int?,
    val providerTotalTokens: Int?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
