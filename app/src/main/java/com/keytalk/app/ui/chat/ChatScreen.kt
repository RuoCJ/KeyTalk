package com.keytalk.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.Message
import com.keytalk.app.domain.model.MessageRole
import com.keytalk.app.domain.model.MessageStatus
import com.keytalk.app.domain.model.ModelProfile
import com.keytalk.app.domain.model.PreparedImageAttachment
import com.keytalk.app.domain.service.ContextPressure
import com.keytalk.app.domain.service.ContextUsage

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var input by rememberSaveable { mutableStateOf("") }
    var modelSwitcherExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val messageListState = rememberLazyListState()
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::addImage)
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) pendingCameraUri?.let(viewModel::addImage)
        pendingCameraUri = null
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = viewModel.createCameraOutputUri()
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            viewModel.showFeedback("相机权限未授予；可改用“选择图片”。")
        }
    }

    BackHandler(enabled = modelSwitcherExpanded) {
        modelSwitcherExpanded = false
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content, state.messages.lastOrNull()?.status) {
        if (state.messages.isNotEmpty()) {
            messageListState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactChatTopBar(
            contextUsage = state.contextUsage,
            onBack = onBack,
        )

        state.feedback?.let { Text(it) }
        if (!state.supportsVision) {
            Text("当前模型未启用视觉能力；图片按钮不可用。")
        } else if (!hasCamera) {
            Text("当前设备未检测到相机；可使用“选择图片”。")
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = messageListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = state.messages,
                key = { _, message -> message.id },
            ) { index, message ->
                val nextMessage = state.messages.getOrNull(index + 1)
                MessageBubble(
                    message = message,
                    showRetry = message.role == MessageRole.USER &&
                        nextMessage?.role == MessageRole.ASSISTANT &&
                        nextMessage.status == MessageStatus.FAILED &&
                        !state.isSending,
                    onRetry = { viewModel.retryMessage(message.id) },
                    onCopyMarkdown = {
                        clipboardManager.setText(AnnotatedString(copyableMessageContent(message, markdown = true)))
                        viewModel.showFeedback("已复制 Markdown。")
                    },
                    onCopyPlainText = {
                        clipboardManager.setText(AnnotatedString(copyableMessageContent(message, markdown = false)))
                        viewModel.showFeedback(
                            if (message.role == MessageRole.USER) "已复制问题文本。" else "已复制纯文本。",
                        )
                    },
                )
            }
        }

        if (state.pendingImages.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("待发送图片")
                state.pendingImages.forEach { image ->
                    PendingImagePreview(
                        image = image,
                        onRemove = { viewModel.removePendingImage(image.id) },
                    )
                }
            }
        }
        ChatInputBox(
            input = input,
            onInputChange = { input = it },
            currentModel = state.currentModel,
            currentConnection = state.currentConnection,
            availableModels = state.availableModels,
            connections = state.connections,
            modelSwitcherExpanded = modelSwitcherExpanded,
            onModelSwitcherExpandedChange = { modelSwitcherExpanded = it },
            onSwitchModel = viewModel::switchModel,
            canSend = input.isNotBlank() || state.pendingImages.isNotEmpty(),
            canPickImage = state.supportsVision && !state.isSending,
            canTakePhoto = state.supportsVision && hasCamera && !state.isSending,
            isSending = state.isSending,
            onPickImage = {
                pickImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onTakePhoto = {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onSend = {
                modelSwitcherExpanded = false
                viewModel.sendMessage(input)
                input = ""
            },
            onStop = { viewModel.stopGeneration() },
        )
    }
}

@Composable
private fun CompactChatTopBar(
    contextUsage: ContextUsage,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        ) {
            Text(
                text = "‹",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "聊天",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ContextUsagePill(
            contextUsage = contextUsage,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ContextUsagePill(
    contextUsage: ContextUsage,
    modifier: Modifier = Modifier,
) {
    val containerColor = contextUsage.containerColor()
    val contentColor = contextUsage.contentColor()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            text = "${contextUsage.usedTokens} / ${contextUsage.limitTokens} " +
                "(${(contextUsage.ratio * 100).toInt()}%) · ${contextUsage.pressure.label()}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationModelSwitcher(
    currentModel: ModelProfile?,
    currentConnection: ConnectionProfile?,
    availableModels: List<ModelProfile>,
    connections: List<ConnectionProfile>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isSending: Boolean,
    onSwitchModel: (String) -> Unit,
) {
    val modelsForSwitching = (listOfNotNull(currentModel) + availableModels)
        .filter { model -> availableModels.any { it.id == model.id } || model.id == currentModel?.id }
        .distinctBy { "${it.connectionId}:${it.model.trim().lowercase()}" }
    val connectionById = connections.associateBy { it.id }
    val modelGroups = modelsForSwitching
        .groupBy { it.connectionId }
        .map { (connectionId, models) ->
            ModelSwitchGroup(
                connectionId = connectionId,
                connection = connectionById[connectionId],
                models = models,
            )
        }
    val groupIds = modelGroups.map { it.connectionId }
    val defaultExpandedGroupId = currentModel?.connectionId ?: modelGroups.firstOrNull()?.connectionId
    var expandedGroupIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val switcherScrollState = rememberScrollState()

    LaunchedEffect(expanded, defaultExpandedGroupId, groupIds.joinToString("|")) {
        if (expanded && expandedGroupIds.none { it in groupIds }) {
            expandedGroupIds = defaultExpandedGroupId?.let(::listOf).orEmpty()
        }
    }

    if (!expanded) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "当前模型",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentModel?.displayName ?: "未找到模型配置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${currentConnection?.name ?: "未知连接"} · ${currentModel?.model ?: "-"} · " +
                            "上下文 ${currentModel?.effectiveContextLabel() ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { onExpandedChange(false) },
                    enabled = !isSending,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("收起")
                }
            }
            HorizontalDivider()
            Text(
                text = "按连接/提供商分组；选择后会立即保存到当前会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ModelSwitcherExpandedMaxHeight)
                    .verticalScroll(switcherScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modelGroups.forEach { group ->
                    val groupExpanded = group.connectionId in expandedGroupIds
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.connection?.name ?: "未知连接",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${group.connection?.protocolAdapter?.displayName() ?: "未知提供商"} · " +
                                            "${group.models.size} 个模型" +
                                            if (group.connectionId == currentModel?.connectionId) " · 当前连接" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        expandedGroupIds = if (groupExpanded) {
                                            expandedGroupIds - group.connectionId
                                        } else {
                                            expandedGroupIds + group.connectionId
                                        }
                                    },
                                    enabled = !isSending,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(if (groupExpanded) "折叠" else "展开")
                                }
                            }
                            if (groupExpanded) {
                                group.models.chunked(2).forEach { rowModels ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        rowModels.forEach { model ->
                                            FilterChip(
                                                selected = model.id == currentModel?.id,
                                                onClick = {
                                                    onSwitchModel(model.id)
                                                    onExpandedChange(false)
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = !isSending,
                                                label = {
                                                    Text(
                                                        text = if (model.id == currentModel?.id) {
                                                            "✓ ${model.displayName}\n${model.model}"
                                                        } else {
                                                            "${model.displayName}\n${model.model}"
                                                        },
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                            )
                                        }
                                        if (rowModels.size == 1) {
                                            Box(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ModelSwitchGroup(
    val connectionId: String,
    val connection: ConnectionProfile?,
    val models: List<ModelProfile>,
)

private val ModelSwitcherExpandedMaxHeight = 300.dp

private fun ModelProfile.effectiveContextLabel(): String =
    if (supports1mContext && enable1mContext) "1M" else defaultContextWindow.toString()

@Composable
private fun ChatInputBox(
    input: String,
    onInputChange: (String) -> Unit,
    currentModel: ModelProfile?,
    currentConnection: ConnectionProfile?,
    availableModels: List<ModelProfile>,
    connections: List<ConnectionProfile>,
    modelSwitcherExpanded: Boolean,
    onModelSwitcherExpandedChange: (Boolean) -> Unit,
    onSwitchModel: (String) -> Unit,
    canSend: Boolean,
    canPickImage: Boolean,
    canTakePhoto: Boolean,
    isSending: Boolean,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isSending) {
        if (isSending) {
            addMenuExpanded = false
            onModelSwitcherExpandedChange(false)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConversationModelSwitcher(
            currentModel = currentModel,
            currentConnection = currentConnection,
            availableModels = availableModels,
            connections = connections,
            expanded = modelSwitcherExpanded,
            onExpandedChange = onModelSwitcherExpandedChange,
            isSending = isSending,
            onSwitchModel = onSwitchModel,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                label = { Text("输入文本消息") },
                minLines = 3,
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputAddMenu(
                    expanded = addMenuExpanded,
                    onExpandedChange = { addMenuExpanded = it },
                    canSwitchModel = availableModels.isNotEmpty() && !isSending,
                    canPickImage = canPickImage,
                    canTakePhoto = canTakePhoto,
                    modelLabel = currentModel?.displayName ?: "未配置",
                    isModelPanelOpen = modelSwitcherExpanded,
                    onSwitchModel = {
                        addMenuExpanded = false
                        onModelSwitcherExpandedChange(!modelSwitcherExpanded)
                    },
                    onPickImage = {
                        addMenuExpanded = false
                        onPickImage()
                    },
                    onTakePhoto = {
                        addMenuExpanded = false
                        onTakePhoto()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 14.dp)
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(50.dp),
                        strokeWidth = 3.dp,
                    )
                }
                CircularInputButton(
                    text = if (isSending) "■" else "↑",
                    enabled = isSending || canSend,
                    onClick = {
                        if (isSending) {
                            addMenuExpanded = false
                            onModelSwitcherExpandedChange(false)
                            onStop()
                        } else {
                            addMenuExpanded = false
                            onModelSwitcherExpandedChange(false)
                            onSend()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InputAddMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canSwitchModel: Boolean,
    canPickImage: Boolean,
    canTakePhoto: Boolean,
    modelLabel: String,
    isModelPanelOpen: Boolean,
    onSwitchModel: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    Box {
        CircularInputButton(
            text = "＋",
            enabled = canSwitchModel || canPickImage || canTakePhoto,
            onClick = { onExpandedChange(!expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 190.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(if (isModelPanelOpen) "收起模型切换" else "切换模型")
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                enabled = canSwitchModel,
                onClick = onSwitchModel,
            )
            DropdownMenuItem(
                text = { Text("选择照片") },
                enabled = canPickImage,
                onClick = onPickImage,
            )
            DropdownMenuItem(
                text = { Text("拍照") },
                enabled = canTakePhoto,
                onClick = onTakePhoto,
            )
        }
    }
}

@Composable
private fun CircularInputButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun PendingImagePreview(
    image: PreparedImageAttachment,
    onRemove: () -> Unit,
) {
    val bitmap = remember(image.id, image.base64Data) {
        runCatching {
            val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "待发送图片预览",
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Card(
                    modifier = Modifier.size(72.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        text = "图片",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("已添加图片", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${image.mimeType} · ${image.width}×${image.height} · ${image.sizeBytes / 1024}KB",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onRemove) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
private fun ContextUsage.containerColor() = when (pressure) {
    ContextPressure.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
    ContextPressure.WARNING_70 -> MaterialTheme.colorScheme.secondaryContainer
    ContextPressure.WARNING_85 -> MaterialTheme.colorScheme.tertiaryContainer
    ContextPressure.CRITICAL_95 -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun ContextUsage.contentColor() = when (pressure) {
    ContextPressure.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ContextPressure.WARNING_70 -> MaterialTheme.colorScheme.onSecondaryContainer
    ContextPressure.WARNING_85 -> MaterialTheme.colorScheme.onTertiaryContainer
    ContextPressure.CRITICAL_95 -> MaterialTheme.colorScheme.onErrorContainer
}

private fun ContextPressure.label(): String = when (this) {
    ContextPressure.NORMAL -> "正常"
    ContextPressure.WARNING_70 -> "70% 提醒"
    ContextPressure.WARNING_85 -> "85% 将压缩早期上下文"
    ContextPressure.CRITICAL_95 -> "95% 临界"
}

@Composable
private fun MessageBubble(
    message: Message,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onCopyPlainText: () -> Unit,
) {
    var showCopyDialog by rememberSaveable(message.id) { mutableStateOf(false) }
    if (showCopyDialog) {
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("复制内容") },
            text = { Text("选择复制 Markdown 源文，还是只复制纯文本内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCopyDialog = false
                        onCopyMarkdown()
                    },
                ) {
                    Text("复制 Markdown")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showCopyDialog = false
                            onCopyPlainText()
                        },
                    ) {
                        Text("复制纯文本")
                    }
                    TextButton(onClick = { showCopyDialog = false }) {
                        Text("取消")
                    }
                }
            },
        )
    }

    val isUser = message.role == MessageRole.USER
    val bubbleAlignment = if (isUser) Alignment.End else Alignment.Start
    val maxWidth = if (isUser) 0.84f else 0.96f
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (message.status == MessageStatus.FAILED) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = bubbleAlignment,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(maxWidth),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = bubbleColor,
            contentColor = contentColor,
            border = border,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isUser) 10.dp else 12.dp,
                    vertical = if (isUser) 8.dp else 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (isUser) 4.dp else 6.dp),
            ) {
                MessageHeader(
                    message = message,
                    isUser = isUser,
                    showRetry = showRetry,
                    onRetry = onRetry,
                    onCopyClick = {
                        if (message.role == MessageRole.USER) {
                            onCopyPlainText()
                        } else {
                            showCopyDialog = true
                        }
                    },
                )
                if (message.attachments.isNotEmpty()) {
                    Text(
                        "图片 ${message.attachments.size} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                    )
                }
                MarkdownText(
                    text = message.content.ifBlank { "…" },
                    compact = isUser,
                )
            }
        }
    }
}

@Composable
private fun MessageHeader(
    message: Message,
    isUser: Boolean,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onCopyClick: () -> Unit,
) {
    val label = when (message.role) {
        MessageRole.USER -> "你"
        MessageRole.ASSISTANT -> "AI"
        MessageRole.SYSTEM -> "系统"
    }
    val statusLabel = when (message.status) {
        MessageStatus.SENDING -> "发送中"
        MessageStatus.STREAMING -> "生成中"
        MessageStatus.COMPLETED -> null
        MessageStatus.FAILED -> "失败"
        MessageStatus.INTERRUPTED -> "已停止"
    }
    val accentColor = if (message.status == MessageStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (message.status == MessageStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        statusLabel?.let {
            Text(
                text = "· $it",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
            )
        }
        if (showRetry) {
            Text(
                text = "重新问",
                modifier = Modifier.clickable(onClick = onRetry),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(modifier = Modifier.weight(1f))
        IconButton(
            modifier = Modifier.size(if (isUser) 28.dp else 32.dp),
            onClick = onCopyClick,
        ) {
            Text(
                text = "⧉",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun copyableMessageContent(message: Message, markdown: Boolean): String {
    if (message.role == MessageRole.USER) return message.content
    val content = message.content.ifBlank {
        if (message.attachments.isNotEmpty()) "[图片]" else ""
    }
    return if (markdown) content else markdownToPlainText(content)
}

private fun markdownToPlainText(markdown: String): String =
    markdown
        .lines()
        .joinToString("\n") { line ->
            line
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^\\s*[-*+]\\s+"), "")
                .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("`{1,3}([^`]*)`{1,3}"), "$1")
                .replace("**", "")
                .replace("__", "")
                .replace("*", "")
                .replace("_", "")
                .trimEnd()
        }

@Composable
private fun MarkdownText(
    text: String,
    compact: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
        text.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                line.startsWith("- ") -> Text(
                    text = "• ${line.removePrefix("- ")}",
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                )
                line.isBlank() -> if (!compact) Text("")
                else -> Text(
                    text = inlineMarkdown(line),
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun inlineMarkdown(text: String) = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val start = text.indexOf("**", startIndex = index)
        if (start < 0) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, start))
        val end = text.indexOf("**", startIndex = start + 2)
        if (end < 0) {
            append(text.substring(start))
            break
        }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 2, end))
        }
        index = end + 2
    }
}
