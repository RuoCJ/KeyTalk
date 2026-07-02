package com.keytalk.app.network

import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.StreamEvent
import com.keytalk.app.domain.model.ProtocolAdapter
import kotlinx.coroutines.flow.Flow

interface ChatNetworkClient {
    suspend fun send(request: ChatRequest): ChatResponse
    fun stream(request: ChatRequest): Flow<StreamEvent>
    suspend fun listModels(
        protocolAdapter: ProtocolAdapter,
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean = false,
    ): List<String> = emptyList()
    fun cancel(requestId: String)
}
