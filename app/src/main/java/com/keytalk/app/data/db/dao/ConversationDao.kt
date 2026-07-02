package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE deleteState = 'ACTIVE' ORDER BY updatedAtMillis DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE deleteState = 'TRASH' ORDER BY deletedAtMillis DESC")
    fun observeTrash(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC")
    suspend fun listAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE deleteState = 'ACTIVE' ORDER BY updatedAtMillis DESC")
    suspend fun listActive(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query(
        """
        UPDATE conversations
        SET deleteState = 'TRASH',
            deletedAtMillis = :deletedAtMillis,
            purgeAfterMillis = :purgeAfterMillis,
            updatedAtMillis = :deletedAtMillis
        WHERE id = :conversationId
        """,
    )
    suspend fun moveToTrash(conversationId: String, deletedAtMillis: Long, purgeAfterMillis: Long)

    @Query(
        """
        UPDATE conversations
        SET deleteState = 'ACTIVE',
            deletedAtMillis = NULL,
            purgeAfterMillis = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :conversationId
        """,
    )
    suspend fun restoreFromTrash(conversationId: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE conversations
        SET title = :title,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :conversationId
        """,
    )
    suspend fun renameConversation(conversationId: String, title: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE conversations
        SET modelProfileId = :modelProfileId,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :conversationId
        """,
    )
    suspend fun switchModel(conversationId: String, modelProfileId: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE conversations
        SET lastMessagePreview = :preview,
            updatedAtMillis = :updatedAtMillis
        WHERE id = :conversationId
        """,
    )
    suspend fun updatePreview(conversationId: String, preview: String, updatedAtMillis: Long)

    @Query("SELECT id FROM conversations WHERE deleteState = 'TRASH' AND purgeAfterMillis IS NOT NULL AND purgeAfterMillis <= :nowMillis")
    suspend fun findExpiredTrashIds(nowMillis: Long): List<String>

    @Query("SELECT id FROM conversations WHERE deleteState = 'TRASH'")
    suspend fun listTrashIds(): List<String>

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteById(conversationId: String)
}
