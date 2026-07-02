package com.keytalk.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keytalk.app.domain.model.ConnectionProfile
import com.keytalk.app.domain.model.ProtocolAdapter

@Composable
fun EditConnectionScreen(
    connection: ConnectionProfile?,
    onSave: (name: String, protocolAdapter: ProtocolAdapter, baseUrl: String, apiKey: String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    formKey: String = connection?.id ?: "new",
) {
    var name by rememberSaveable { mutableStateOf(connection?.name.orEmpty()) }
    var protocolAdapterName by rememberSaveable {
        mutableStateOf((connection?.protocolAdapter ?: ProtocolAdapter.OPENAI_COMPATIBLE).name)
    }
    val protocolAdapter = ProtocolAdapter.entries.firstOrNull { it.name == protocolAdapterName }
        ?: ProtocolAdapter.OPENAI_COMPATIBLE
    var baseUrl by rememberSaveable { mutableStateOf(connection?.baseUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    val isInsecureHttp = baseUrl.trim().startsWith("http://", ignoreCase = true)
    val isNewConnection = connection == null
    val canSave = name.isNotBlank() &&
        baseUrl.isNotBlank() &&
        (!isNewConnection || apiKey.isNotBlank())

    LaunchedEffect(formKey) {
        name = connection?.name.orEmpty()
        protocolAdapterName = (connection?.protocolAdapter ?: ProtocolAdapter.OPENAI_COMPATIBLE).name
        baseUrl = connection?.baseUrl.orEmpty()
        apiKey = ""
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isNewConnection) "新增连接" else "编辑连接",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "选择服务协议，填写 Base URL 和 API Key。Key 只保存在本机安全存储。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onClose) {
                Text("收起")
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("连接名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            text = "Provider Adapter",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProviderAdapterSelector(
            selected = protocolAdapter,
            onSelected = { protocolAdapterName = it.name },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            supportingText = {
                Text(
                    text = "⚠️ API Key 和聊天内容会发送到该服务，请确认可信。\n" +
                        "请使用 HTTPS，HTTP 明文传输存在严重安全风险。",
                    color = if (isInsecureHttp) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (isNewConnection) "API Key" else "API Key（留空表示不修改）") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = canSave,
            onClick = { onSave(name, protocolAdapter, baseUrl, apiKey) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isNewConnection) "保存为新连接" else "保存连接修改")
        }
    }
}

@Composable
private fun ProviderAdapterSelector(
    selected: ProtocolAdapter,
    onSelected: (ProtocolAdapter) -> Unit,
) {
    val adapters = listOf(
        ProtocolAdapter.OPENAI_COMPATIBLE,
        ProtocolAdapter.CLAUDE_NATIVE,
        ProtocolAdapter.GEMINI_NATIVE,
        ProtocolAdapter.GROK_NATIVE,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adapters.chunked(2).forEach { rowAdapters ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowAdapters.forEach { adapter ->
                    AssistChip(
                        onClick = { onSelected(adapter) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = if (selected == adapter) {
                                    "✓ ${adapter.displayName()}"
                                } else {
                                    adapter.displayName()
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                if (rowAdapters.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
