package com.keytalk.app.data.repository

import com.keytalk.app.data.db.dao.ConnectionDao
import com.keytalk.app.data.db.dao.ModelDao
import com.keytalk.app.data.db.entity.toDomain
import com.keytalk.app.data.db.entity.toEntity
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.repository.ConfigurationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConfigurationRepository(
    private val connectionDao: ConnectionDao,
    private val modelDao: ModelDao,
) : ConfigurationRepository {
    override fun observeConnections(): Flow<List<ConnectionProfile>> =
        connectionDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeModels(): Flow<List<ModelProfile>> =
        modelDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeModels(connectionId: String): Flow<List<ModelProfile>> =
        modelDao.observeByConnection(connectionId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun listConnections(): List<ConnectionProfile> =
        connectionDao.listAll().map { it.toDomain() }

    override suspend fun listModels(): List<ModelProfile> =
        modelDao.listAll().map { it.toDomain() }

    override suspend fun getConnection(connectionId: String): ConnectionProfile? =
        connectionDao.getById(connectionId)?.toDomain()

    override suspend fun getModel(modelProfileId: String): ModelProfile? =
        modelDao.getById(modelProfileId)?.toDomain()

    override suspend fun getDefaultModel(): ModelProfile? =
        modelDao.getDefaultOrLatest()?.toDomain()

    override suspend fun saveConnection(connectionProfile: ConnectionProfile) {
        connectionDao.upsert(connectionProfile.toEntity())
    }

    override suspend fun saveModel(modelProfile: ModelProfile) {
        modelDao.upsert(modelProfile.toEntity())
    }

    override suspend fun deleteConnection(connectionProfile: ConnectionProfile) {
        connectionDao.delete(connectionProfile.toEntity())
    }

    override suspend fun deleteModel(modelProfile: ModelProfile) {
        modelDao.delete(modelProfile.toEntity())
    }
}
