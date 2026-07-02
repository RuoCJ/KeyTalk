package com.keytalk.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.BuiltInModelCatalog
import com.keytalk.app.domain.model.ModelReasoningEffort
import com.keytalk.app.domain.model.ProtocolAdapter

@Composable
fun EditModelScreen(
    enabled: Boolean,
    onSave: (
        displayName: String,
        model: String,
        streamEnabled: Boolean,
        visionEnabled: Boolean,
        defaultContextWindow: Int,
        supports1mContext: Boolean,
        enable1mContext: Boolean,
        reasoningEffort: ModelReasoningEffort?,
    ) -> Unit,
    modifier: Modifier = Modifier,
    suggestedModelIds: List<String> = emptyList(),
    protocolAdapter: ProtocolAdapter? = null,
    isSavingModel: Boolean = false,
    onClose: () -> Unit = {},
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var streamEnabled by rememberSaveable { mutableStateOf(true) }
    var visionEnabled by rememberSaveable { mutableStateOf(false) }
    var defaultContextWindow by rememberSaveable { mutableStateOf(AppConfig.Context.defaultContextWindow.toString()) }
    var enable1mContext by rememberSaveable { mutableStateOf(false) }
    var reasoningEffortName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCapability = BuiltInModelCatalog.capabilityFor(model)
    val normalizedSuggestedModelIds = suggestedModelIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val contextWindowValue = defaultContextWindow.toIntOrNull()
        ?: selectedCapability?.defaultContextWindow
        ?: AppConfig.Context.defaultContextWindow
    val inferredSupports1mContext = selectedCapability?.supports1mContext == true ||
        contextWindowValue >= AppConfig.Context.oneMillionWindow
    val isClaudeNativeConnection = protocolAdapter == ProtocolAdapter.CLAUDE_NATIVE
    val canEnableClaude1mContext = isClaudeNativeConnection && inferredSupports1mContext
    val supportedReasoningEfforts = selectedCapability?.reasoningEfforts.orEmpty()
    val reasoningEffort = ModelReasoningEffort.fromStorage(reasoningEffortName)

    fun applyModelSelection(modelId: String) {
        val previousModel = model
        val capability = BuiltInModelCatalog.capabilityFor(modelId)
        model = modelId
        if (displayName.isBlank() || displayName == previousModel) {
            displayName = capability?.displayName ?: modelId
        }
        capability?.let {
            streamEnabled = it.supportsStreaming
            visionEnabled = it.supportsVision
            defaultContextWindow = it.defaultContextWindow.toString()
        }
    }

    LaunchedEffect(canEnableClaude1mContext) {
        if (!canEnableClaude1mContext) {
            enable1mContext = false
        }
    }

    LaunchedEffect(model, supportedReasoningEfforts) {
        if (reasoningEffort != null && reasoningEffort !in supportedReasoningEfforts && supportedReasoningEfforts.isNotEmpty()) {
            reasoningEffortName = null
        }
    }

    Column(modifier = modifier) {
        Text("模型配置")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onClose, enabled = !isSavingModel) {
                Text("收起")
            }
        }
        if (normalizedSuggestedModelIds.isNotEmpty()) {
            Text("可选模型候选（已保存/本次获取 ${normalizedSuggestedModelIds.size} 个，点击自动填入）")
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val visibleCandidateIds = normalizedSuggestedModelIds.take(MaxVisibleModelCandidates)
                visibleCandidateIds.chunked(2).forEach { rowModels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowModels.forEach { modelId ->
                            FilterChip(
                                selected = model == modelId,
                                onClick = { applyModelSelection(modelId) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        text = if (model == modelId) "✓ $modelId" else modelId,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                        if (rowModels.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (normalizedSuggestedModelIds.size > MaxVisibleModelCandidates) {
                    Text(
                        text = "候选模型较多，当前显示前 $MaxVisibleModelCandidates 个；也可以在下方手动填写真实 model 名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (model.isNotBlank()) {
                Text(
                    text = "已选择：$model",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                text = "没有可选模型候选。可先点击“获取模型列表”；如果中转站不支持 /models，请直接在下方手动填写真实 model 名称。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("显示名") },
            supportingText = { Text("给自己看的名称，例如：Oumi GPT-4o mini") },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("真实 model 名称") },
            supportingText = { Text("必须填写当前中转站/服务商支持的模型 ID；获取不到列表时以中转站文档或控制台为准。") },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
        )
        selectedCapability?.let { capability ->
            Text(
                text = "已识别能力：上下文 ${capability.defaultContextWindow} tokens · " +
                    "视觉 ${if (capability.supportsVision) "支持" else "不支持"} · " +
                    "1M 级上下文 ${if (capability.supports1mContext) "支持/可尝试" else "未确认"} · " +
                    "推理级别 ${if (capability.reasoningEfforts.isNotEmpty()) capability.reasoningEfforts.joinToString { it.displayName } else "未识别"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } ?: Text(
            text = "未在内置能力库中识别该模型；/models 通常只返回 model ID，无法可靠给出上下文能力，请按中转站/模型文档手动填写真实上下文窗口。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = streamEnabled,
                onCheckedChange = { streamEnabled = it },
                enabled = enabled,
            )
            Text("启用流式输出")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = visionEnabled,
                onCheckedChange = { visionEnabled = it },
                enabled = enabled,
            )
            Text("启用视觉 / 图片输入")
        }
        OutlinedTextField(
            value = defaultContextWindow,
            onValueChange = { defaultContextWindow = it.filter(Char::isDigit) },
            label = { Text("默认上下文窗口 tokens") },
            supportingText = {
                Text(
                    if (enable1mContext) {
                        "已启用 Claude 1M beta：本地请求按 1,000,000 tokens 管理，此输入暂不生效。"
                    } else {
                        "用于本地上下文预算和压缩；OpenAI-Compatible/Gemini 等模型支持长上下文时，直接填写真实窗口即可。"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !enable1mContext,
            singleLine = true,
        )
        Text(
            text = if (inferredSupports1mContext) {
                "1M 能力提示：已根据内置能力库或你填写的上下文窗口判断为 1M 级模型；请以中转站文档/实际测活为准。"
            } else {
                "1M 能力提示：当前无法确认支持 1M。中转站 /models 通常不给上下文元数据；如果文档确认支持，请把默认上下文窗口填写为 1000000 或真实值。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (inferredSupports1mContext) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isClaudeNativeConnection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enable1mContext,
                    onCheckedChange = { enable1mContext = it },
                    enabled = enabled && inferredSupports1mContext,
                )
                Text("启用 Claude 1M beta 参数")
            }
            Text(
                text = "只有 Claude Native 需要这个开关；未识别模型需先填写 ≥1000000 的上下文窗口后才可启用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "当前连接为 ${protocolAdapter?.displayName() ?: "未选择连接"}；无需勾选 1M 支持。若该模型支持长上下文，保存真实上下文窗口即可生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "推理 / 思考级别",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (supportedReasoningEfforts.isEmpty()) {
                "未能自动识别该模型支持哪些级别；/models 通常不会返回此信息。若服务商文档确认支持，可选择通用级别尝试。"
            } else {
                "已根据内置能力库识别可选级别；不同中转站可能仍会忽略或拒绝该参数。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (supportedReasoningEfforts.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        ReasoningEffortSelector(
            selected = reasoningEffort,
            efforts = supportedReasoningEfforts.ifEmpty { GenericReasoningEfforts },
            onSelected = { reasoningEffortName = it?.name },
            enabled = enabled,
        )
        Button(
            onClick = {
                if (isSavingModel) return@Button
                onSave(
                    displayName,
                    model,
                    streamEnabled,
                    visionEnabled,
                    contextWindowValue,
                    inferredSupports1mContext,
                    enable1mContext && canEnableClaude1mContext,
                    reasoningEffort,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !isSavingModel && displayName.isNotBlank() && model.isNotBlank(),
        ) {
            Text(if (isSavingModel) "保存中..." else "保存模型")
        }
        Text("MVP-B 支持多 Provider Adapter；只有勾选视觉能力的模型才允许发送图片。")
    }
}

private const val MaxVisibleModelCandidates = 60

private val GenericReasoningEfforts = listOf(
    ModelReasoningEffort.LOW,
    ModelReasoningEffort.MEDIUM,
    ModelReasoningEffort.HIGH,
    ModelReasoningEffort.XHIGH,
    ModelReasoningEffort.MAX,
)

@Composable
private fun ReasoningEffortSelector(
    selected: ModelReasoningEffort?,
    efforts: List<ModelReasoningEffort>,
    onSelected: (ModelReasoningEffort?) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf<ModelReasoningEffort?>(null) + efforts).chunked(3).forEach { rowEfforts ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowEfforts.forEach { effort ->
                    FilterChip(
                        selected = selected == effort,
                        enabled = enabled,
                        onClick = { onSelected(effort) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = effort?.displayName ?: "默认",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                repeat(3 - rowEfforts.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
