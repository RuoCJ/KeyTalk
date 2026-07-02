package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.ConversationSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationSummaryDao {
    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    fun observeByConversation(conversationId: String): Flow<List<ConversationSummaryEntity>>

    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC")
    suspend fun listByConversation(conversationId: String): List<ConversationSummaryEntity>

    @Query("SELECT * FROM conversation_summaries ORDER BY createdAtMillis ASC")
    suspend fun listAll(): List<ConversationSummaryEntity>

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE conversationId = :conversationId
          AND coveredMessageStartId = :startId
          AND coveredMessageEndId = :endId
        LIMIT 1
        """,
    )
    suspend fun findCoveringRange(conversationId: String, startId: String?, endId: String?): ConversationSummaryEntity?

    @Upsert
    suspend fun upsert(summary: ConversationSummaryEntity)
}
