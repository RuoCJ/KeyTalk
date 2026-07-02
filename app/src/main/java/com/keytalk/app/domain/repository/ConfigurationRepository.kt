package com.keytalk.app.domain.repository

import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ModelProfile
import kotlinx.coroutines.flow.Flow

interface ConfigurationRepository {
    fun observeConnections(): Flow<List<ConnectionProfile>>
    fun observeModels(): Flow<List<ModelProfile>>
    fun observeModels(connectionId: String): Flow<List<ModelProfile>>
    suspend fun listConnections(): List<ConnectionProfile>
    suspend fun listModels(): List<ModelProfile>
    suspend fun getConnection(connectionId: String): ConnectionProfile?
    suspend fun getModel(modelProfileId: String): ModelProfile?
    suspend fun getDefaultModel(): ModelProfile?
    suspend fun saveConnection(connectionProfile: ConnectionProfile)
    suspend fun saveModel(modelProfile: ModelProfile)
    suspend fun deleteConnection(connectionProfile: ConnectionProfile)
    suspend fun deleteModel(modelProfile: ModelProfile)
}
