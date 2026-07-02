package com.keytalk.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_profiles")
data class ConnectionProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocolAdapter: String,
    val baseUrl: String,
    val credentialId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
