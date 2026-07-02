package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "model_profiles",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class ModelProfileEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val displayName: String,
    val model: String,
    val modelSource: String,
    val supportsStreaming: Boolean,
    val supportsVision: Boolean,
    val defaultContextWindow: Int,
    val supports1mContext: Boolean,
    val enable1mContext: Boolean,
    val temperature: Double?,
    val maxTokens: Int?,
    val reasoningEffort: String?,
    val isDefault: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
