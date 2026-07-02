package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.ModelProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM model_profiles ORDER BY isDefault DESC, updatedAtMillis DESC")
    fun observeAll(): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM model_profiles WHERE connectionId = :connectionId ORDER BY isDefault DESC, updatedAtMillis DESC")
    fun observeByConnection(connectionId: String): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM model_profiles ORDER BY isDefault DESC, updatedAtMillis DESC")
    suspend fun listAll(): List<ModelProfileEntity>

    @Query("SELECT * FROM model_profiles WHERE id = :id")
    suspend fun getById(id: String): ModelProfileEntity?

    @Query("SELECT * FROM model_profiles ORDER BY isDefault DESC, updatedAtMillis DESC LIMIT 1")
    suspend fun getDefaultOrLatest(): ModelProfileEntity?

    @Upsert
    suspend fun upsert(model: ModelProfileEntity)

    @Delete
    suspend fun delete(model: ModelProfileEntity)
}
