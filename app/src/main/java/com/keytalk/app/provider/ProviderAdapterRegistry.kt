package com.keytalk.app.provider

import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.claude.ClaudeNativeAdapter
import com.keytalk.app.provider.gemini.GeminiNativeAdapter
import com.keytalk.app.provider.grok.GrokNativeAdapter
import com.keytalk.app.provider.openai.OpenAICompatibleAdapter

class ProviderAdapterRegistry(
    private val adapters: Map<ProtocolAdapter, ProviderAdapter>,
) {
    fun adapterFor(protocolAdapter: ProtocolAdapter): ProviderAdapter =
        adapters[protocolAdapter] ?: adapters[ProtocolAdapter.OPENAI_COMPATIBLE]
            ?: error("未注册服务协议适配器：$protocolAdapter")

    companion object {
        fun default(): ProviderAdapterRegistry {
            val openAiCompatible = OpenAICompatibleAdapter()
            return ProviderAdapterRegistry(
                mapOf(
                    ProtocolAdapter.OPENAI_COMPATIBLE to openAiCompatible,
                    ProtocolAdapter.CLAUDE_NATIVE to ClaudeNativeAdapter(),
                    ProtocolAdapter.GEMINI_NATIVE to GeminiNativeAdapter(),
                    ProtocolAdapter.GROK_NATIVE to GrokNativeAdapter(),
                    ProtocolAdapter.CUSTOM to openAiCompatible,
                ),
            )
        }

        fun single(adapter: ProviderAdapter): ProviderAdapterRegistry =
            ProviderAdapterRegistry(
                ProtocolAdapter.entries.associateWith { adapter },
            )
    }
}
