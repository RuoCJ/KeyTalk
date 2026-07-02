package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = ModelProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["modelProfileId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("modelProfileId"),
        Index("deleteState"),
        Index("purgeAfterMillis"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelProfileId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessagePreview: String,
    val deleteState: String,
    val deletedAtMillis: Long?,
    val purgeAfterMillis: Long?,
)
