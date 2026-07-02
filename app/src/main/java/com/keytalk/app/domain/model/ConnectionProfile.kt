package com.keytalk.app.domain.model

import java.time.Instant
import java.util.UUID

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocolAdapter: ProtocolAdapter = ProtocolAdapter.OPENAI_COMPATIBLE,
    val baseUrl: String,
    val credentialId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "连接名称不能为空。" }
        require(baseUrl.isNotBlank()) { "Base URL 不能为空。" }
        require(credentialId.isNotBlank()) { "凭据标识不能为空。" }
    }
}
