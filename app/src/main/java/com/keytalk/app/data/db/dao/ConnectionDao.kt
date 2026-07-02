package com.keytalk.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.keytalk.app.data.db.entity.ConnectionProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection_profiles ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profiles ORDER BY updatedAtMillis DESC")
    suspend fun listAll(): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profiles WHERE id = :id")
    suspend fun getById(id: String): ConnectionProfileEntity?

    @Upsert
    suspend fun upsert(connection: ConnectionProfileEntity)

    @Delete
    suspend fun delete(connection: ConnectionProfileEntity)
}
