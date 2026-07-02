package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_summaries",
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
        Index("coveredMessageStartId"),
        Index("coveredMessageEndId"),
    ],
)
data class ConversationSummaryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val summaryContent: String,
    val coveredMessageStartId: String?,
    val coveredMessageEndId: String?,
    val tokenEstimate: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
