package com.keytalk.app.provider

import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.claude.ClaudeNativeAdapter
import com.keytalk.app.provider.gemini.GeminiNativeAdapter
import com.keytalk.app.provider.grok.GrokNativeAdapter
import com.keytalk.app.provider.openai.OpenAICompatibleAdapter
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAdapterRegistryTest {
    @Test
    fun defaultRegistryProvidesNativeAdaptersForAllMvpBProviders() {
        val registry = ProviderAdapterRegistry.default()

        assertTrue(registry.adapterFor(ProtocolAdapter.OPENAI_COMPATIBLE) is OpenAICompatibleAdapter)
        assertTrue(registry.adapterFor(ProtocolAdapter.CLAUDE_NATIVE) is ClaudeNativeAdapter)
        assertTrue(registry.adapterFor(ProtocolAdapter.GEMINI_NATIVE) is GeminiNativeAdapter)
        assertTrue(registry.adapterFor(ProtocolAdapter.GROK_NATIVE) is GrokNativeAdapter)
        assertTrue(registry.adapterFor(ProtocolAdapter.CUSTOM) is OpenAICompatibleAdapter)
    }

    @Test
    fun singleRegistryMapsEveryProtocolToSameAdapterForFocusedTests() {
        val adapter = OpenAICompatibleAdapter()
        val registry = ProviderAdapterRegistry.single(adapter)

        ProtocolAdapter.entries.forEach { protocol ->
            assertTrue(registry.adapterFor(protocol) === adapter)
        }
    }
}
