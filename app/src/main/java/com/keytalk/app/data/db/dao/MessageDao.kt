package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    suspend fun listByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query(
        """
        UPDATE messages
        SET content = :content,
            status = :status,
            tokenEstimate = :tokenEstimate,
            providerInputTokens = :providerInputTokens,
            providerOutputTokens = :providerOutputTokens,
            providerTotalTokens = :providerTotalTokens,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :messageId
        """,
    )
    suspend fun updateMessage(
        messageId: String,
        content: String,
        status: String,
        tokenEstimate: Int,
        providerInputTokens: Int?,
        providerOutputTokens: Int?,
        providerTotalTokens: Int?,
        updatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE messages
        SET content = content || :delta,
            status = :status,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :messageId
        """,
    )
    suspend fun appendContent(messageId: String, delta: String, status: String, updatedAtMillis: Long)
}
