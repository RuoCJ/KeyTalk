package com.keytalk.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keytalk.app.backup.BackupCrypto
import com.keytalk.app.backup.BackupImportMode
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.ModelReasoningEffort
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.ui.toUserFacingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class SettingsPage {
    HOME,
    CONNECTIONS,
    MODELS,
    TEST_AND_FAILOVER,
    BACKUP,
    ;

    companion object {
        fun fromName(name: String): SettingsPage =
            entries.firstOrNull { it.name == name } ?: HOME
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.HOME.name) }
    val page = SettingsPage.fromName(pageName)
    fun openPage(nextPage: SettingsPage) {
        pageName = nextPage.name
    }
    var connectionFormVisible by rememberSaveable { mutableStateOf(false) }
    var isCreatingConnection by rememberSaveable { mutableStateOf(false) }
    var newConnectionFormRevision by rememberSaveable { mutableIntStateOf(0) }
    var modelConfigExpanded by rememberSaveable(state.selectedConnectionId) { mutableStateOf(false) }
    var savedModelsExpanded by rememberSaveable(state.selectedConnectionId) { mutableStateOf(false) }
    var allModelsExpanded by rememberSaveable { mutableStateOf(false) }
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var includeApiKeys by rememberSaveable { mutableStateOf(false) }
    var confirmApiKeyExport by rememberSaveable { mutableStateOf(false) }
    var includeTrash by rememberSaveable { mutableStateOf(false) }
    var importModeName by rememberSaveable { mutableStateOf(BackupImportMode.MERGE.name) }
    val importMode = BackupImportMode.entries.firstOrNull { it.name == importModeName } ?: BackupImportMode.MERGE
    var confirmOverwrite by rememberSaveable { mutableStateOf(false) }
    var fileFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var connectionSaveRequested by remember { mutableStateOf(false) }
    var modelSaveRequested by remember { mutableStateOf(false) }
    var wasSavingModel by remember { mutableStateOf(false) }
    val selectedConnection = state.selectedConnection
    val connectionBeingEdited = if (isCreatingConnection) null else selectedConnection
    val hasSavedModelsForSelectedConnection = state.modelsForSelectedConnection.isNotEmpty()
    val modelCountsByConnection = state.distinctModels.groupingBy { it.connectionId }.eachCount()
    val backupPasswordValidation = BackupCrypto.validateBackupPassword(backupPassword.toCharArray())
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val backupText = viewModel.exportBackupText(
                password = backupPassword,
                includeApiKeys = includeApiKeys,
                includeTrash = includeTrash,
            ).getOrNull() ?: return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(backupText.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入备份文件")
                }
            }.onSuccess {
                fileFeedback = "备份文件已保存。"
            }.onFailure { throwable ->
                fileFeedback = throwable.toUserFacingMessage("写入备份文件失败")
            }
        }
    }
    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val backupText = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取备份文件")
                }
            }.onFailure { throwable ->
                fileFeedback = throwable.toUserFacingMessage("读取备份文件失败")
            }.getOrNull() ?: return@launch
            viewModel.importBackupText(
                backupJson = backupText,
                password = backupPassword,
                mode = importMode,
            )
        }
    }

    BackHandler(enabled = page != SettingsPage.HOME) {
        openPage(SettingsPage.HOME)
    }

    LaunchedEffect(state.feedback) {
        if (connectionSaveRequested) {
            val saveSucceeded = state.feedback == "新连接已保存，API Key 只写入系统安全存储。" ||
                state.feedback == "连接修改已保存，API Key 只写入系统安全存储。"
            val saveFailed = state.feedback?.startsWith("保存连接失败") == true
            if (saveSucceeded) {
                connectionFormVisible = false
                isCreatingConnection = false
                connectionSaveRequested = false
            } else if (saveFailed) {
                connectionSaveRequested = false
            }
        }
    }

    LaunchedEffect(state.isSavingModel, state.feedback) {
        if (wasSavingModel && !state.isSavingModel && modelSaveRequested) {
            if (state.feedback == "模型已保存。" || state.feedback == "模型已更新。") {
                modelConfigExpanded = false
                savedModelsExpanded = false
            }
            modelSaveRequested = false
        }
        wasSavingModel = state.isSavingModel
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHeader(
            title = when (page) {
                SettingsPage.HOME -> "设置"
                SettingsPage.CONNECTIONS -> "连接与密钥"
                SettingsPage.MODELS -> "模型管理"
                SettingsPage.TEST_AND_FAILOVER -> "测试与故障切换"
                SettingsPage.BACKUP -> "备份与恢复"
            },
            subtitle = when (page) {
                SettingsPage.HOME -> "配置连接、模型、测试策略和本地加密备份。"
                SettingsPage.CONNECTIONS -> "管理服务协议、Base URL 和本机安全存储里的 API Key。"
                SettingsPage.MODELS -> "获取、保存和维护每个连接下可用的模型。"
                SettingsPage.TEST_AND_FAILOVER -> "验证当前连接可用性，并配置失败重试候选连接。"
                SettingsPage.BACKUP -> "导出或导入本地加密备份。备份密码不会保存。"
            },
            onBack = {
                if (page == SettingsPage.HOME) onBack() else openPage(SettingsPage.HOME)
            },
        )

        state.feedback?.let { Text(it) }
        if (page == SettingsPage.BACKUP) {
            fileFeedback?.let { Text(it) }
        }

        when (page) {
            SettingsPage.HOME -> SettingsHomePage(
                state = state,
                modelCountsByConnection = modelCountsByConnection,
                onOpenConnections = { openPage(SettingsPage.CONNECTIONS) },
                onOpenModels = { openPage(SettingsPage.MODELS) },
                onOpenTestAndFailover = { openPage(SettingsPage.TEST_AND_FAILOVER) },
                onOpenBackup = { openPage(SettingsPage.BACKUP) },
            )

            SettingsPage.CONNECTIONS -> ConnectionsSettingsPage(
                state = state,
                selectedConnection = selectedConnection,
                connectionBeingEdited = connectionBeingEdited,
                modelCountsByConnection = modelCountsByConnection,
                connectionFormVisible = connectionFormVisible,
                isCreatingConnection = isCreatingConnection,
                newConnectionFormRevision = newConnectionFormRevision,
                onShowNewConnection = {
                    connectionFormVisible = true
                    isCreatingConnection = true
                    newConnectionFormRevision += 1
                },
                onShowEditConnection = {
                    connectionFormVisible = true
                    isCreatingConnection = false
                },
                onHideConnectionForm = {
                    connectionFormVisible = false
                    isCreatingConnection = false
                    connectionSaveRequested = false
                },
                onSaveConnection = { name, protocolAdapter, baseUrl, apiKey ->
                    connectionSaveRequested = true
                    val editingConnectionId = connectionBeingEdited?.id
                    viewModel.saveConnection(editingConnectionId, name, protocolAdapter, baseUrl, apiKey)
                    if (editingConnectionId == null) {
                        isCreatingConnection = true
                        newConnectionFormRevision += 1
                    } else {
                        isCreatingConnection = false
                    }
                },
                onSelectConnection = { connectionId ->
                    connectionFormVisible = false
                    isCreatingConnection = false
                    viewModel.selectConnection(connectionId)
                },
            )

            SettingsPage.MODELS -> ModelSettingsPage(
                state = state,
                selectedConnection = selectedConnection,
                hasSavedModelsForSelectedConnection = hasSavedModelsForSelectedConnection,
                modelConfigExpanded = modelConfigExpanded,
                savedModelsExpanded = savedModelsExpanded,
                allModelsExpanded = allModelsExpanded,
                onFetchRemoteModels = viewModel::fetchRemoteModels,
                onModelConfigExpandedChange = { modelConfigExpanded = it },
                onSavedModelsExpandedChange = { savedModelsExpanded = it },
                onAllModelsExpandedChange = { allModelsExpanded = it },
                onSaveModel = { displayName, model, streamEnabled, visionEnabled, defaultContextWindow, supports1mContext, enable1mContext, reasoningEffort ->
                    modelConfigExpanded = true
                    savedModelsExpanded = false
                    modelSaveRequested = true
                    viewModel.saveModel(
                        displayName = displayName,
                        modelName = model,
                        streamEnabled = streamEnabled,
                        visionEnabled = visionEnabled,
                        defaultContextWindow = defaultContextWindow,
                        supports1mContext = supports1mContext,
                        enable1mContext = enable1mContext,
                        reasoningEffort = reasoningEffort,
                    )
                },
                onCloseModelConfig = {
                    modelConfigExpanded = false
                    savedModelsExpanded = false
                    modelSaveRequested = false
                },
                onOpenConnections = { openPage(SettingsPage.CONNECTIONS) },
            )

            SettingsPage.TEST_AND_FAILOVER -> TestAndFailoverSettingsPage(
                state = state,
                selectedConnection = selectedConnection,
                modelCountsByConnection = modelCountsByConnection,
                onTestSelectedConnection = viewModel::testSelectedConnection,
                onToggleFailoverConnection = viewModel::toggleFailoverConnection,
                onOpenConnections = { openPage(SettingsPage.CONNECTIONS) },
                onOpenModels = { openPage(SettingsPage.MODELS) },
            )

            SettingsPage.BACKUP -> BackupSettingsPage(
                backupPassword = backupPassword,
                backupPasswordValidation = backupPasswordValidation,
                includeApiKeys = includeApiKeys,
                confirmApiKeyExport = confirmApiKeyExport,
                includeTrash = includeTrash,
                importMode = importMode,
                confirmOverwrite = confirmOverwrite,
                onBackupPasswordChange = { backupPassword = it },
                onIncludeApiKeysChange = {
                    includeApiKeys = it
                    if (!it) confirmApiKeyExport = false
                },
                onConfirmApiKeyExportChange = { confirmApiKeyExport = it },
                onIncludeTrashChange = { includeTrash = it },
                onImportModeChange = {
                    importModeName = it.name
                    if (it != BackupImportMode.OVERWRITE) confirmOverwrite = false
                },
                onConfirmOverwriteChange = { confirmOverwrite = it },
                onExportBackup = { createBackupLauncher.launch(defaultBackupFileName()) },
                onImportBackup = { openBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            )
        }
    }
}

@Composable
private fun SettingsHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Text("‹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsHomePage(
    state: SettingsUiState,
    modelCountsByConnection: Map<String, Int>,
    onOpenConnections: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenTestAndFailover: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val selectedConnection = state.selectedConnection
    SettingsEntryCard(
        title = "连接与密钥",
        description = selectedConnection?.let {
            "${state.connections.size} 个连接；当前：${it.name} · ${it.protocolAdapter.displayName()}"
        } ?: "还没有连接。先添加服务商或中转站连接。",
        actionText = "管理连接",
        onClick = onOpenConnections,
    )
    SettingsEntryCard(
        title = "模型管理",
        description = if (selectedConnection == null) {
            "保存连接后，可获取模型列表或手动添加模型。"
        } else {
            val count = modelCountsByConnection[selectedConnection.id] ?: 0
            "当前连接 ${selectedConnection.name} 下有 $count 个模型；全部模型库 ${state.distinctModels.size} 个。"
        },
        actionText = "管理模型",
        onClick = onOpenModels,
    )
    SettingsEntryCard(
        title = "测试与故障切换",
        description = "连接状态：${state.connectionModelTestStatus.displayName()}；故障切换候选 ${state.failoverConnectionIds.size} 个。",
        actionText = "测试与配置",
        onClick = onOpenTestAndFailover,
    )
    SettingsEntryCard(
        title = "备份与恢复",
        description = "导出或导入本地加密备份，可选择是否包含 API Key 和回收站。",
        actionText = "打开备份",
        onClick = onOpenBackup,
    )
}

@Composable
private fun SettingsEntryCard(title: String, description: String, actionText: String, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(actionText) }
        }
    }
}

@Composable
private fun ConnectionsSettingsPage(
    state: SettingsUiState,
    selectedConnection: ConnectionProfile?,
    connectionBeingEdited: ConnectionProfile?,
    modelCountsByConnection: Map<String, Int>,
    connectionFormVisible: Boolean,
    isCreatingConnection: Boolean,
    newConnectionFormRevision: Int,
    onShowNewConnection: () -> Unit,
    onShowEditConnection: () -> Unit,
    onHideConnectionForm: () -> Unit,
    onSaveConnection: (String, ProtocolAdapter, String, String) -> Unit,
    onSelectConnection: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onShowNewConnection, modifier = Modifier.weight(1f)) { Text("新增连接") }
        OutlinedButton(
            onClick = onShowEditConnection,
            enabled = state.selectedConnection != null,
            modifier = Modifier.weight(1f),
        ) { Text("编辑当前") }
    }

    if (connectionFormVisible) {
        Text(
            text = if (isCreatingConnection) {
                "当前为新增连接模式：保存后会创建新连接，不会覆盖连接列表里的已有连接。"
            } else {
                "当前为编辑模式：保存会修改选中的连接。要添加另一个连接，请先点“新增连接”。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isCreatingConnection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("连接表单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onHideConnectionForm) { Text("收起") }
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            EditConnectionScreen(
                connection = connectionBeingEdited,
                onClose = onHideConnectionForm,
                onSave = onSaveConnection,
                modifier = Modifier.padding(16.dp),
                formKey = connectionBeingEdited?.id ?: "new-$newConnectionFormRevision",
            )
        }
    } else {
        Text(
            text = if (state.connections.isEmpty()) "还没有连接。点击“新增连接”后再填写。" else "连接表单默认收起；需要新增或修改时再点击上方按钮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.connections.isEmpty()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("先保存连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("连接保存成功后，可进入模型管理获取/保存模型。")
            }
        }
    } else {
        Text("连接列表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.connections.chunked(2).forEach { rowConnections ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowConnections.forEach { connection ->
                        val modelCount = modelCountsByConnection[connection.id] ?: 0
                        val isSelected = state.selectedConnection?.id == connection.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectConnection(connection.id) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = "${if (isSelected) "✓ " else ""}${connection.name} · ${if (modelCount > 0) "$modelCount 模型" else "未获取"}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (rowConnections.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        selectedConnection?.let {
            Text(
                "当前连接：${it.name} · ${it.protocolAdapter.displayName()} · ${modelCountsByConnection[it.id] ?: 0} 个模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ModelSettingsPage(
    state: SettingsUiState,
    selectedConnection: ConnectionProfile?,
    hasSavedModelsForSelectedConnection: Boolean,
    modelConfigExpanded: Boolean,
    savedModelsExpanded: Boolean,
    allModelsExpanded: Boolean,
    onFetchRemoteModels: () -> Unit,
    onModelConfigExpandedChange: (Boolean) -> Unit,
    onSavedModelsExpandedChange: (Boolean) -> Unit,
    onAllModelsExpandedChange: (Boolean) -> Unit,
    onSaveModel: (String, String, Boolean, Boolean, Int, Boolean, Boolean, ModelReasoningEffort?) -> Unit,
    onCloseModelConfig: () -> Unit,
    onOpenConnections: () -> Unit,
) {
    if (state.connections.isEmpty()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("先保存连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("模型属于具体连接。请先到“连接与密钥”添加一个连接。")
                Button(onClick = onOpenConnections) { Text("去添加连接") }
            }
        }
        return
    }

    selectedConnection?.let {
        Text(
            "当前连接：${it.name} · ${it.protocolAdapter.displayName()} · ${state.modelsForSelectedConnection.size} 个模型",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    OutlinedButton(onClick = onFetchRemoteModels, enabled = state.selectedConnection != null) {
        Text(selectedConnection?.let { "获取「${it.name}」的模型列表" } ?: "获取模型列表")
    }
    Text(
        text = when {
            selectedConnection == null -> "保存连接后可先获取模型列表。"
            hasSavedModelsForSelectedConnection ->
                "已保存 ${state.modelsForSelectedConnection.size} 个「${selectedConnection.name}」模型；其他连接的模型会继续保留。"
            else -> "只会获取当前连接「${selectedConnection.name}」的模型；如果中转站不支持 /models，可展开模型配置手动填写。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SavedModelsSection(
        connection = selectedConnection,
        models = state.modelsForSelectedConnection,
        expanded = savedModelsExpanded,
        onExpandedChange = onSavedModelsExpandedChange,
    )
    AllConnectionModelLibrary(
        connections = state.connections,
        models = state.distinctModels,
        expanded = allModelsExpanded,
        onExpandedChange = onAllModelsExpandedChange,
    )
    HorizontalDivider()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasSavedModelsForSelectedConnection) {
                            "已保存模型时默认折叠；不需要测试也可以直接保存/修改模型。"
                        } else {
                            "当前连接还没有模型；如果中转站不允许测试或 /models，请直接手动添加并保存。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasSavedModelsForSelectedConnection) {
                    OutlinedButton(onClick = { onModelConfigExpandedChange(!modelConfigExpanded) }) {
                        Text(if (modelConfigExpanded) "收起" else "展开")
                    }
                }
            }
            if (!hasSavedModelsForSelectedConnection || modelConfigExpanded) {
                val savedModelIds = state.modelsForSelectedConnection.map { it.model }
                val modelCandidateIds = (state.remoteModelIds + savedModelIds)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.modelIdentityKey() }
                EditModelScreen(
                    enabled = state.selectedConnection != null,
                    suggestedModelIds = modelCandidateIds,
                    protocolAdapter = state.selectedConnection?.protocolAdapter,
                    isSavingModel = state.isSavingModel,
                    onSave = onSaveModel,
                    onClose = onCloseModelConfig,
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun TestAndFailoverSettingsPage(
    state: SettingsUiState,
    selectedConnection: ConnectionProfile?,
    modelCountsByConnection: Map<String, Int>,
    onTestSelectedConnection: () -> Unit,
    onToggleFailoverConnection: (String) -> Unit,
    onOpenConnections: () -> Unit,
    onOpenModels: () -> Unit,
) {
    if (state.connections.isEmpty()) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("还没有连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("请先添加连接，然后保存至少一个模型再测试。")
                Button(onClick = onOpenConnections) { Text("去添加连接") }
            }
        }
        return
    }

    selectedConnection?.let {
        Text(
            "当前连接：${it.name} · ${it.protocolAdapter.displayName()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Button(
        onClick = onTestSelectedConnection,
        enabled = state.selectedConnection != null && state.modelsForSelectedConnection.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("测试连接和模型")
    }
    Text(
        text = if (state.modelsForSelectedConnection.isEmpty()) {
            "测试前需要至少保存一个当前连接下的模型。你可以先到“模型管理”手动保存模型。"
        } else {
            "测试是可选项；有些中转站会禁用测试请求或 /models，不影响你手动保存模型。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.modelsForSelectedConnection.isEmpty()) {
        OutlinedButton(onClick = onOpenModels) { Text("去保存模型") }
    }
    ConnectionModelTestStatusCard(
        status = state.connectionModelTestStatus,
        message = state.connectionModelTestMessage,
    )
    HorizontalDivider()
    FailoverConnectionsSection(
        connections = state.connections,
        selectedConnectionIds = state.failoverConnectionIds,
        modelCountsByConnection = modelCountsByConnection,
        onToggleConnection = onToggleFailoverConnection,
    )
}

@Composable
private fun BackupSettingsPage(
    backupPassword: String,
    backupPasswordValidation: String?,
    includeApiKeys: Boolean,
    confirmApiKeyExport: Boolean,
    includeTrash: Boolean,
    importMode: BackupImportMode,
    confirmOverwrite: Boolean,
    onBackupPasswordChange: (String) -> Unit,
    onIncludeApiKeysChange: (Boolean) -> Unit,
    onConfirmApiKeyExportChange: (Boolean) -> Unit,
    onIncludeTrashChange: (Boolean) -> Unit,
    onImportModeChange: (BackupImportMode) -> Unit,
    onConfirmOverwriteChange: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("备份与恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = backupPassword,
                onValueChange = onBackupPasswordChange,
                label = { Text("备份密码（不会保存）") },
                supportingText = { Text(backupPasswordValidation ?: "至少 8 个字符；密码不会保存在 App 中。") },
                isError = backupPasswordValidation != null,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row {
                Checkbox(checked = includeApiKeys, onCheckedChange = onIncludeApiKeysChange)
                Text("导出时包含 API Key（将进入加密 payload）")
            }
            if (includeApiKeys) {
                Row {
                    Checkbox(checked = confirmApiKeyExport, onCheckedChange = onConfirmApiKeyExportChange)
                    Text("二次确认：我理解备份文件可解密出 API Key，会妥善保管备份密码与文件")
                }
            }
            Row {
                Checkbox(checked = includeTrash, onCheckedChange = onIncludeTrashChange)
                Text("导出时包含回收站")
            }
            Text("导入模式")
            BackupImportModeSelector(selected = importMode, onSelected = onImportModeChange)
            if (importMode == BackupImportMode.OVERWRITE) {
                Row {
                    Checkbox(checked = confirmOverwrite, onCheckedChange = onConfirmOverwriteChange)
                    Text("确认覆盖当前本地数据（会删除现有会话、配置与本机 API Key 引用）")
                }
            }
            Button(
                enabled = backupPasswordValidation == null && (!includeApiKeys || confirmApiKeyExport),
                modifier = Modifier.fillMaxWidth(),
                onClick = onExportBackup,
            ) {
                Text("导出加密备份")
            }
            Button(
                enabled = backupPasswordValidation == null && (importMode != BackupImportMode.OVERWRITE || confirmOverwrite),
                modifier = Modifier.fillMaxWidth(),
                onClick = onImportBackup,
            ) {
                Text("导入加密备份")
            }
            Text("提示：备份密码不保存在 App 中；包含 API Key 前请确认导出文件会被妥善保管。")
        }
    }
}

private fun ConnectionModelTestStatus.displayName(): String = when (this) {
    ConnectionModelTestStatus.NOT_TESTED -> "未测试"
    ConnectionModelTestStatus.TESTING -> "测试中"
    ConnectionModelTestStatus.SUCCESS -> "可用"
    ConnectionModelTestStatus.FAILED -> "不可用"
}

@Composable
internal fun SavedModelsSection(
    connection: ConnectionProfile?,
    models: List<ModelProfile>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前连接下的模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            connection == null -> "请先选择或保存一个连接。"
                            models.isEmpty() -> "「${connection.name}」暂无模型。点击“获取「${connection.name}」的模型列表”或手动添加。"
                            else -> "「${connection.name}」已保存 ${models.size} 个模型；切换其他连接不会删除这里的模型。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (models.isNotEmpty()) {
                    OutlinedButton(onClick = { onExpandedChange(!expanded) }) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
            }

            if (expanded && models.isNotEmpty()) {
                models.forEach { model ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(model.displayName)
                            Text("model=${model.model}")
                            Text(if (model.supportsStreaming) "流式：开启" else "流式：关闭")
                            Text(if (model.supportsVision) "视觉：开启" else "视觉：关闭")
                            Text("上下文：${if (model.enable1mContext) "1M" else model.defaultContextWindow.toString()}")
                            Text("推理级别：${model.reasoningEffort?.displayName ?: "默认"}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AllConnectionModelLibrary(
    connections: List<ConnectionProfile>,
    models: List<ModelProfile>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val modelsByConnection = models.groupBy { it.connectionId }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "全部连接模型库",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "共 ${connections.size} 个连接、${models.size} 个模型；按连接分组保留。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            if (expanded) {
                connections.forEach { connection ->
                    val connectionModels = modelsByConnection[connection.id].orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${connection.name} · ${connection.protocolAdapter.displayName()} · ${connectionModels.size} 个模型",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (connectionModels.isEmpty()) {
                                Text(
                                    text = "未获取模型。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                connectionModels.take(AppConfig.Settings.modelLibraryPreviewCount).forEach { model ->
                                    Text(
                                        text = "• ${model.displayName}（${model.model}）",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (connectionModels.size > AppConfig.Settings.modelLibraryPreviewCount) {
                                    Text(
                                        text = "还有 ${connectionModels.size - AppConfig.Settings.modelLibraryPreviewCount} 个模型未展开显示。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FailoverConnectionsSection(
    connections: List<ConnectionProfile>,
    selectedConnectionIds: Set<String>,
    modelCountsByConnection: Map<String, Int>,
    onToggleConnection: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "高级：自动故障切换候选连接",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "聊天发送失败时，会在已勾选连接中优先尝试同名 model；没有同名模型时使用该连接的默认/最新模型重试。获取模型列表不受这里影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            connections.chunked(2).forEach { rowConnections ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowConnections.forEach { connection ->
                        val modelCount = modelCountsByConnection[connection.id] ?: 0
                        FilterChip(
                            selected = connection.id in selectedConnectionIds,
                            onClick = { onToggleConnection(connection.id) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    text = if (connection.id in selectedConnectionIds) {
                                        "✓ ${connection.name} · $modelCount"
                                    } else {
                                        "${connection.name} · $modelCount"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                    if (rowConnections.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConnectionModelTestStatusCard(
    status: ConnectionModelTestStatus,
    message: String?,
) {
    val title = when (status) {
        ConnectionModelTestStatus.NOT_TESTED -> "连接/模型状态：未测试"
        ConnectionModelTestStatus.TESTING -> "连接/模型状态：测试中"
        ConnectionModelTestStatus.SUCCESS -> "连接/模型状态：可用"
        ConnectionModelTestStatus.FAILED -> "连接/模型状态：不可用"
    }
    val containerColor = when (status) {
        ConnectionModelTestStatus.NOT_TESTED -> MaterialTheme.colorScheme.surfaceVariant
        ConnectionModelTestStatus.TESTING -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionModelTestStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        ConnectionModelTestStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status) {
        ConnectionModelTestStatus.NOT_TESTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionModelTestStatus.TESTING -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionModelTestStatus.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionModelTestStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message ?: "测试为可选项；如果中转站不允许测试，可直接保存连接和模型后使用。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun BackupImportModeSelector(
    selected: BackupImportMode,
    onSelected: (BackupImportMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackupImportMode.entries.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowModes.forEach { mode ->
                    AssistChip(
                        onClick = { onSelected(mode) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = if (selected == mode) "✓ ${mode.name}" else mode.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                if (rowModes.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun defaultBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = BackupFileNameFormatter.withZone(zoneId).format(now)
    return "keytalk-backup-$timestamp-mvp-b.json"
}

private val BackupFileNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
