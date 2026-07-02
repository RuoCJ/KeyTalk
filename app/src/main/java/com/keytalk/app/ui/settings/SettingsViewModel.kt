package com.keytalk.app.ui.settings

import com.keytalk.app.backup.BackupImportMode
import com.keytalk.app.backup.BackupImportResult
import com.keytalk.app.backup.BackupCrypto
import com.keytalk.app.backup.KeyTalkBackupService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.BuiltInModelCatalog
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.ModelReasoningEffort
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.domain.repository.ConfigurationRepository
import com.keytalk.app.domain.repository.FailoverPolicyRepository
import com.keytalk.app.image.ImageCacheMaintenanceService
import com.keytalk.app.network.ChatNetworkClient
import com.keytalk.app.provider.ChatMessage
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.security.CredentialStore
import com.keytalk.app.ui.toUserFacingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

enum class ModelProbeStatus {
    TESTING,
    AVAILABLE,
    UNAVAILABLE,
}

enum class ConnectionModelTestStatus {
    NOT_TESTED,
    TESTING,
    SUCCESS,
    FAILED,
}

private data class SettingsEphemeralState(
    val selectedConnectionId: String?,
    val remoteModelIds: List<String>,
    val modelProbeResults: Map<String, ModelProbeStatus>,
    val connectionModelTestStatus: ConnectionModelTestStatus,
    val connectionModelTestMessage: String?,
    val failoverConnectionIds: Set<String>,
    val feedback: String?,
    val isSavingModel: Boolean,
)

private data class ModelDiscoveryState(
    val selectedConnectionId: String?,
    val remoteModelIds: List<String>,
    val modelProbeResults: Map<String, ModelProbeStatus>,
)

private data class TestFeedbackState(
    val connectionModelTestStatus: ConnectionModelTestStatus,
    val connectionModelTestMessage: String?,
    val failoverConnectionIds: Set<String>,
    val feedback: String?,
    val isSavingModel: Boolean,
)

data class SettingsUiState(
    val connections: List<ConnectionProfile> = emptyList(),
    val models: List<ModelProfile> = emptyList(),
    val selectedConnectionId: String? = null,
    val remoteModelIds: List<String> = emptyList(),
    val modelProbeResults: Map<String, ModelProbeStatus> = emptyMap(),
    val connectionModelTestStatus: ConnectionModelTestStatus = ConnectionModelTestStatus.NOT_TESTED,
    val connectionModelTestMessage: String? = null,
    val failoverConnectionIds: Set<String> = emptySet(),
    val feedback: String? = null,
    val isSavingModel: Boolean = false,
) {
    val selectedConnection: ConnectionProfile? =
        connections.firstOrNull { it.id == selectedConnectionId } ?: connections.firstOrNull()

    val modelsForSelectedConnection: List<ModelProfile> =
        selectedConnection
            ?.let { connection -> models.filter { it.connectionId == connection.id } }
            .orEmpty()
            .distinctByConnectionAndModel()

    val distinctModels: List<ModelProfile> = models.distinctByConnectionAndModel()
}

class SettingsViewModel(
    private val configurationRepository: ConfigurationRepository,
    private val credentialStore: CredentialStore,
    private val networkClient: ChatNetworkClient,
    private val backupService: KeyTalkBackupService,
    private val failoverPolicyRepository: FailoverPolicyRepository,
    private val imageCacheMaintenance: ImageCacheMaintenanceService? = null,
) : ViewModel() {
    private val selectedConnectionId = MutableStateFlow<String?>(null)
    private val remoteModelIds = MutableStateFlow<List<String>>(emptyList())
    private val modelProbeResults = MutableStateFlow<Map<String, ModelProbeStatus>>(emptyMap())
    private val connectionModelTestStatus = MutableStateFlow(ConnectionModelTestStatus.NOT_TESTED)
    private val connectionModelTestMessage = MutableStateFlow<String?>(null)
    private val failoverConnectionIds = MutableStateFlow(failoverPolicyRepository.getEnabledConnectionIds())
    private val feedback = MutableStateFlow<String?>(null)
    private val isSavingModel = MutableStateFlow(false)

    private val modelDiscoveryState = combine(
        selectedConnectionId,
        remoteModelIds,
        modelProbeResults,
    ) { selected, discoveredModels, probeResults ->
        ModelDiscoveryState(
            selectedConnectionId = selected,
            remoteModelIds = discoveredModels,
            modelProbeResults = probeResults,
        )
    }

    private val testFeedbackState = combine(
        connectionModelTestStatus,
        connectionModelTestMessage,
        failoverConnectionIds,
        feedback,
        isSavingModel,
    ) { status, message, failoverIds, currentFeedback, savingModel ->
        TestFeedbackState(
            connectionModelTestStatus = status,
            connectionModelTestMessage = message,
            failoverConnectionIds = failoverIds,
            feedback = currentFeedback,
            isSavingModel = savingModel,
        )
    }

    private val ephemeralState = combine(
        modelDiscoveryState,
        testFeedbackState,
    ) { modelDiscovery, testFeedback ->
        SettingsEphemeralState(
            selectedConnectionId = modelDiscovery.selectedConnectionId,
            remoteModelIds = modelDiscovery.remoteModelIds,
            modelProbeResults = modelDiscovery.modelProbeResults,
            connectionModelTestStatus = testFeedback.connectionModelTestStatus,
            connectionModelTestMessage = testFeedback.connectionModelTestMessage,
            failoverConnectionIds = testFeedback.failoverConnectionIds,
            feedback = testFeedback.feedback,
            isSavingModel = testFeedback.isSavingModel,
        )
    }

    val uiState = combine(
        configurationRepository.observeConnections(),
        configurationRepository.observeModels(),
        ephemeralState,
    ) { connections, models, ephemeral ->
        val resolvedSelected = ephemeral.selectedConnectionId ?: connections.firstOrNull()?.id
        SettingsUiState(
            connections = connections,
            models = models,
            selectedConnectionId = resolvedSelected,
            remoteModelIds = ephemeral.remoteModelIds,
            modelProbeResults = ephemeral.modelProbeResults,
            connectionModelTestStatus = ephemeral.connectionModelTestStatus,
            connectionModelTestMessage = ephemeral.connectionModelTestMessage,
            failoverConnectionIds = ephemeral.failoverConnectionIds,
            feedback = ephemeral.feedback,
            isSavingModel = ephemeral.isSavingModel,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(AppConfig.Settings.uiStateStopTimeoutMillis),
        initialValue = SettingsUiState(),
    )

    fun selectConnection(connectionId: String) {
        selectedConnectionId.value = connectionId
        remoteModelIds.value = emptyList()
        modelProbeResults.value = emptyMap()
        connectionModelTestStatus.value = ConnectionModelTestStatus.NOT_TESTED
        connectionModelTestMessage.value = null
    }

    fun toggleFailoverConnection(connectionId: String) {
        val updated = if (connectionId in failoverConnectionIds.value) {
            failoverConnectionIds.value - connectionId
        } else {
            failoverConnectionIds.value + connectionId
        }
        failoverConnectionIds.value = updated
        failoverPolicyRepository.saveEnabledConnectionIds(updated)
        feedback.value = if (updated.isEmpty()) {
            "已关闭自动故障切换候选连接。"
        } else {
            "已保存 ${updated.size} 个自动故障切换候选连接。"
        }
    }

    fun saveConnection(
        connectionId: String?,
        name: String,
        protocolAdapter: ProtocolAdapter,
        baseUrl: String,
        apiKey: String,
    ) {
        viewModelScope.launch {
            runCatching {
                val now = Instant.now()
                val selected = connectionId?.let { id ->
                    configurationRepository.getConnection(id)
                        ?: error("要编辑的连接不存在，请重新选择。")
                }
                val normalizedName = name.trim()
                val normalizedBaseUrl = baseUrl.trim()
                if (normalizedName.isBlank()) error("连接名称不能为空")
                if (normalizedBaseUrl.isBlank()) error("Base URL 不能为空")

                val credentialId = selected?.credentialId ?: "cred_${UUID.randomUUID()}"
                if (apiKey.isNotBlank()) {
                    credentialStore.saveApiKey(credentialId, apiKey.trim())
                } else if (selected == null) {
                    error("首次保存连接必须填写 API Key")
                }

                val connection = ConnectionProfile(
                    id = selected?.id ?: UUID.randomUUID().toString(),
                    name = normalizedName,
                    protocolAdapter = protocolAdapter,
                    baseUrl = normalizedBaseUrl,
                    credentialId = credentialId,
                    createdAt = selected?.createdAt ?: now,
                    updatedAt = now,
                )
                configurationRepository.saveConnection(connection)
                selectedConnectionId.value = connection.id
                remoteModelIds.value = emptyList()
                modelProbeResults.value = emptyMap()
                connectionModelTestStatus.value = ConnectionModelTestStatus.NOT_TESTED
                connectionModelTestMessage.value = null
                feedback.value = if (selected == null) {
                    "新连接已保存，API Key 只写入系统安全存储。"
                } else {
                    "连接修改已保存，API Key 只写入系统安全存储。"
                }
            }.onFailure { throwable ->
                feedback.value = throwable.toUserFacingMessage("保存连接失败")
            }
        }
    }

    fun saveModel(
        displayName: String,
        modelName: String,
        streamEnabled: Boolean,
        visionEnabled: Boolean,
        defaultContextWindow: Int,
        supports1mContext: Boolean,
        enable1mContext: Boolean,
        reasoningEffort: ModelReasoningEffort? = null,
    ) {
        if (isSavingModel.value) return
        viewModelScope.launch {
            isSavingModel.value = true
            try {
                runCatching {
                    val connection = uiState.value.selectedConnection
                        ?: error("请先保存一个连接")
                    val normalizedDisplayName = displayName.trim()
                    val normalizedModelName = modelName.trim()
                    if (normalizedDisplayName.isBlank()) error("模型显示名称不能为空")
                    if (normalizedModelName.isBlank()) error("模型标识不能为空")
                    val now = Instant.now()
                    val normalizedContextWindow = defaultContextWindow.coerceAtLeast(1)
                    val modelSupports1mContext = supports1mContext ||
                        normalizedContextWindow >= AppConfig.Context.oneMillionWindow
                    val existingModels = configurationRepository.listModels()
                        .filter { it.connectionId == connection.id }
                    val existingModel = existingModels.firstOrNull {
                        it.model.modelIdentityKey() == normalizedModelName.modelIdentityKey()
                    }
                    val model = ModelProfile(
                        id = existingModel?.id ?: UUID.randomUUID().toString(),
                        connectionId = connection.id,
                        displayName = normalizedDisplayName,
                        model = normalizedModelName,
                        modelSource = connection.protocolAdapter.modelSourceName(),
                        supportsStreaming = streamEnabled,
                        supportsVision = visionEnabled,
                        defaultContextWindow = normalizedContextWindow,
                        supports1mContext = modelSupports1mContext,
                        enable1mContext = enable1mContext && modelSupports1mContext,
                        reasoningEffort = reasoningEffort,
                        isDefault = existingModel?.isDefault ?: existingModels.isEmpty(),
                        createdAt = existingModel?.createdAt ?: now,
                        updatedAt = now,
                    )
                    configurationRepository.saveModel(model)
                    deleteDuplicateModelsForConnectionModel(
                        connectionId = connection.id,
                        modelName = normalizedModelName,
                        keepModelId = model.id,
                    )
                    connectionModelTestStatus.value = ConnectionModelTestStatus.NOT_TESTED
                    connectionModelTestMessage.value = null
                    feedback.value = if (existingModel == null) {
                        "模型已保存。"
                    } else {
                        "模型已更新。"
                    }
                }.onFailure { throwable ->
                    feedback.value = throwable.toUserFacingMessage("保存模型失败")
                }
            } finally {
                isSavingModel.value = false
            }
        }
    }

    fun fetchRemoteModels() {
        viewModelScope.launch {
            feedback.value = "正在获取模型列表..."
            remoteModelIds.value = emptyList()
            modelProbeResults.value = emptyMap()
            val connection = uiState.value.selectedConnection
            if (connection == null) {
                feedback.value = "请先保存一个连接"
                return@launch
            }
            runCatching {
                val apiKey = credentialStore.readApiKey(connection.credentialId)
                    ?: error("当前连接没有可用 API Key")
                networkClient.listModels(
                    protocolAdapter = connection.protocolAdapter,
                    baseUrl = connection.baseUrl,
                    apiKey = apiKey,
                )
            }.onSuccess { models ->
                remoteModelIds.value = models
                val savedCount = saveDiscoveredModels(connection, models)
                feedback.value = if (models.isEmpty()) {
                    "模型列表为空；该服务可能未开放 /models，请手动填写模型 ID。"
                } else {
                    "已获取 ${models.size} 个模型，已自动保存 $savedCount 个新模型；会话内“切换”可直接选择这些模型。"
                }
            }.onFailure { throwable ->
                feedback.value = when (throwable) {
                    is ProviderException -> throwable.error.message
                    else -> throwable.toUserFacingMessage("获取模型列表失败")
                }
            }
        }
    }

    private suspend fun saveDiscoveredModels(
        connection: ConnectionProfile,
        discoveredModelIds: List<String>,
    ): Int {
        val existingModels = configurationRepository.listModels()
            .filter { it.connectionId == connection.id }
        val existingModelIds = existingModels.map { it.model.modelIdentityKey() }.toMutableSet()
        val uniqueModelIds = discoveredModelIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.modelIdentityKey() }
            .take(AppConfig.Settings.maxAutoSaveDiscoveredModels)
        var savedCount = 0
        val now = Instant.now()
        uniqueModelIds.forEach { modelId ->
            val modelKey = modelId.modelIdentityKey()
            if (modelKey in existingModelIds) return@forEach
            val capability = BuiltInModelCatalog.capabilityFor(modelId)
            configurationRepository.saveModel(
                ModelProfile(
                    connectionId = connection.id,
                    displayName = capability?.displayName ?: modelId,
                    model = modelId,
                    modelSource = connection.protocolAdapter.modelSourceName(),
                    supportsStreaming = capability?.supportsStreaming ?: true,
                    supportsVision = capability?.supportsVision ?: false,
                    defaultContextWindow = capability?.defaultContextWindow ?: AppConfig.Context.defaultContextWindow,
                    supports1mContext = capability?.supports1mContext ?: false,
                    enable1mContext = false,
                    reasoningEffort = null,
                    isDefault = existingModels.isEmpty() && savedCount == 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            existingModelIds += modelKey
            savedCount += 1
        }
        return savedCount
    }

    private suspend fun deleteDuplicateModelsForConnectionModel(
        connectionId: String,
        modelName: String,
        keepModelId: String,
    ) {
        val modelKey = modelName.modelIdentityKey()
        configurationRepository.listModels()
            .filter {
                it.connectionId == connectionId &&
                    it.model.modelIdentityKey() == modelKey &&
                    it.id != keepModelId
            }
            .forEach { duplicate ->
                configurationRepository.deleteModel(duplicate)
            }
    }

    fun probeModels(modelIds: List<String>) {
        val candidates = modelIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return
        viewModelScope.launch {
            val connection = uiState.value.selectedConnection
            if (connection == null) {
                feedback.value = "请先保存一个连接"
                return@launch
            }
            val apiKey = credentialStore.readApiKey(connection.credentialId)
            if (apiKey == null) {
                feedback.value = "当前连接没有可用 API Key"
                return@launch
            }

            feedback.value = "正在测活 ${candidates.size} 个模型..."
            modelProbeResults.value = modelProbeResults.value + candidates.associateWith { ModelProbeStatus.TESTING }

            var available = 0
            candidates.forEach { modelId ->
                val status = runCatching {
                    networkClient.send(
                        ChatRequest(
                            protocolAdapter = connection.protocolAdapter,
                            baseUrl = connection.baseUrl,
                            apiKey = apiKey,
                            model = modelId,
                            messages = listOf(
                                ChatMessage(
                                    com.keytalk.app.domain.model.MessageRole.USER,
                                    "现在几点了？",
                                ),
                            ),
                            stream = false,
                            temperature = AppConfig.Settings.modelProbeTemperature,
                            maxTokens = AppConfig.Settings.modelProbeMaxTokens,
                        ),
                    )
                }.fold(
                    onSuccess = {
                        available += 1
                        ModelProbeStatus.AVAILABLE
                    },
                    onFailure = { ModelProbeStatus.UNAVAILABLE },
                )
                modelProbeResults.value = modelProbeResults.value + (modelId to status)
            }
            feedback.value = "测活完成：${available}/${candidates.size} 个模型可访问。点击可用模型可自动填入。"
        }
    }

    fun testSelectedConnection() {
        viewModelScope.launch {
            connectionModelTestStatus.value = ConnectionModelTestStatus.TESTING
            connectionModelTestMessage.value = "正在测试当前连接和第一个模型..."
            feedback.value = "正在测试连接..."
            runCatching {
                val connection = uiState.value.selectedConnection
                    ?: error("请先保存一个连接")
                val model = uiState.value.modelsForSelectedConnection.firstOrNull()
                    ?: error("请先保存一个模型")
                val apiKey = credentialStore.readApiKey(connection.credentialId)
                    ?: error("当前连接没有可用 API Key")

                val response = networkClient.send(
                    ChatRequest(
                        protocolAdapter = connection.protocolAdapter,
                        baseUrl = connection.baseUrl,
                        apiKey = apiKey,
                        model = model.model,
                        messages = listOf(ChatMessage(com.keytalk.app.domain.model.MessageRole.USER, "现在几点了？")),
                        stream = false,
                        temperature = model.temperature,
                        maxTokens = AppConfig.Settings.connectionTestMaxTokens,
                        contextWindow = model.defaultContextWindow,
                        enable1mContext = model.enable1mContext,
                    ),
                )
                val message = "连接可用；模型可用：${model.displayName}（${model.model}）。返回：${
                    response.messageText.take(AppConfig.Network.modelTestPreviewChars).ifBlank { "已收到响应" }
                }"
                connectionModelTestStatus.value = ConnectionModelTestStatus.SUCCESS
                connectionModelTestMessage.value = message
                feedback.value = message
            }.onFailure { throwable ->
                val message = when (throwable) {
                    is ProviderException -> throwable.error.message
                    else -> throwable.toUserFacingMessage("测试连接失败")
                }
                connectionModelTestStatus.value = ConnectionModelTestStatus.FAILED
                connectionModelTestMessage.value = "连接或模型不可用：$message"
                feedback.value = message
            }
        }
    }

    suspend fun exportBackupText(
        password: String,
        includeApiKeys: Boolean,
        includeTrash: Boolean,
    ): Result<String> =
        runCatching {
            BackupCrypto.validateBackupPassword(password.toCharArray())?.let { throw IllegalArgumentException(it) }
            backupService.exportEncryptedBackup(
                password = password.toCharArray(),
                includeApiKeys = includeApiKeys,
                includeTrash = includeTrash,
            )
        }.onSuccess {
            feedback.value = "加密备份已导出。"
        }.onFailure { throwable ->
            feedback.value = throwable.toUserFacingMessage("导出备份失败")
        }

    suspend fun importBackupText(
        backupJson: String,
        password: String,
        mode: BackupImportMode,
    ): Result<BackupImportResult> {
        val result = runCatching {
            BackupCrypto.validateBackupPassword(password.toCharArray())?.let { throw IllegalArgumentException(it) }
            backupService.importEncryptedBackup(
                backupJson = backupJson,
                password = password.toCharArray(),
                mode = mode,
            )
        }
        if (result.isSuccess) {
            try {
                imageCacheMaintenance?.cleanupOrphanedImages()
            } catch (_: Throwable) {
            }
            feedback.value = result.getOrNull()!!.toSettingsFeedbackMessage()
        } else {
            feedback.value = result.exceptionOrNull()!!.toUserFacingMessage("导入备份失败")
        }
        return result
    }

    private fun ProtocolAdapter.modelSourceName(): String = when (this) {
        ProtocolAdapter.CLAUDE_NATIVE -> "Claude"
        ProtocolAdapter.GEMINI_NATIVE -> "Gemini"
        ProtocolAdapter.GROK_NATIVE -> "Grok"
        ProtocolAdapter.OPENAI_COMPATIBLE,
        ProtocolAdapter.CUSTOM,
        -> AppConfig.Provider.defaultModelSourceName
    }
}

internal fun BackupImportResult.toSettingsFeedbackMessage(): String =
    buildString {
        append("导入完成：连接 $importedConnections，模型 $importedModels，会话 $importedConversations，消息 $importedMessages。")
        if (importedAttachments > 0) {
            append(" 附件 $importedAttachments 个已迁移。")
        }
        if (remappedCredentials > 0) {
            append(" API Key 已重新写入本机安全存储 $remappedCredentials 个。")
        }
        if (skippedExpiredTrash > 0) {
            append(" 已跳过过期回收站 $skippedExpiredTrash 个。")
        }
    }

internal fun String.modelIdentityKey(): String = trim().lowercase()

internal fun List<ModelProfile>.distinctByConnectionAndModel(): List<ModelProfile> =
    filter { it.model.modelIdentityKey().isNotBlank() }
        .distinctBy { "${it.connectionId}:${it.model.modelIdentityKey()}" }
