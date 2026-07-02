package com.keytalk.app.domain.model

import com.keytalk.app.config.AppConfig

data class BuiltInModelCapability(
    val id: String,
    val displayName: String = id,
    val defaultContextWindow: Int,
    val supportsStreaming: Boolean = true,
    val supportsVision: Boolean = false,
    val supports1mContext: Boolean = false,
    val reasoningEfforts: List<ModelReasoningEffort> = emptyList(),
)

data class BuiltInModelProvider(
    val id: String,
    val displayName: String,
    val models: List<BuiltInModelCapability>,
) {
    val modelIds: List<String> = models.map { it.id }
}

object BuiltInModelCatalog {
    private val DefaultReasoningEfforts = listOf(
        ModelReasoningEffort.LOW,
        ModelReasoningEffort.MEDIUM,
        ModelReasoningEffort.HIGH,
    )

    val providers: List<BuiltInModelProvider> = listOf(
        BuiltInModelProvider(
            id = "openai",
            displayName = "OpenAI",
            models = listOf(
                model("gpt-4o", AppConfig.Context.defaultContextWindow, vision = true),
                model("gpt-4o-mini", AppConfig.Context.defaultContextWindow, vision = true),
                model("gpt-4.1", AppConfig.ModelCatalog.openAiGpt41Context, vision = true),
                model("gpt-4.1-mini", AppConfig.ModelCatalog.openAiGpt41Context, vision = true),
                model("gpt-4.1-nano", AppConfig.ModelCatalog.openAiGpt41Context, vision = true),
                model("gpt-5", AppConfig.Context.oneMillionWindow, vision = true),
                model("gpt-5-mini", AppConfig.Context.oneMillionWindow, vision = true),
                model("gpt-5-nano", AppConfig.Context.oneMillionWindow, vision = true),
                reasoningModel("o3", AppConfig.ModelCatalog.context200k, vision = true),
                reasoningModel("o3-mini", AppConfig.ModelCatalog.context200k),
                reasoningModel("o4-mini", AppConfig.ModelCatalog.context200k, vision = true),
            ),
        ),
        BuiltInModelProvider(
            id = "anthropic",
            displayName = "Anthropic",
            models = listOf(
                model("claude-3-5-sonnet-latest", AppConfig.ModelCatalog.context200k, vision = true),
                model("claude-3-5-haiku-latest", AppConfig.ModelCatalog.context200k, vision = true),
                model("claude-3-7-sonnet-latest", AppConfig.ModelCatalog.context200k, vision = true),
                reasoningModel("claude-sonnet-4", AppConfig.ModelCatalog.context200k, vision = true, oneMillion = true),
                reasoningModel("claude-opus-4", AppConfig.ModelCatalog.context200k, vision = true, oneMillion = true),
            ),
        ),
        BuiltInModelProvider(
            id = "alibaba",
            displayName = "阿里 / Qwen",
            models = listOf(
                model("qwen-plus", AppConfig.Context.defaultContextWindow),
                model("qwen-turbo", AppConfig.Context.oneMillionWindow),
                model("qwen-max", AppConfig.ModelCatalog.context32k),
                model("qwen-long", AppConfig.ModelCatalog.qwenLongContext),
                model("qwen-vl-plus", AppConfig.Context.defaultContextWindow, vision = true),
                model("qwen-vl-max", AppConfig.Context.defaultContextWindow, vision = true),
            ),
        ),
        BuiltInModelProvider(
            id = "deepseek",
            displayName = "DeepSeek",
            models = listOf(
                model("deepseek-chat", AppConfig.ModelCatalog.context64k),
                reasoningModel("deepseek-reasoner", AppConfig.ModelCatalog.context64k),
            ),
        ),
        BuiltInModelProvider(
            id = "google",
            displayName = "Google / Gemini",
            models = listOf(
                model("gemini-1.5-pro", AppConfig.ModelCatalog.gemini15ProContext, vision = true),
                model("gemini-1.5-flash", AppConfig.Context.oneMillionWindow, vision = true),
                model("gemini-2.0-flash", AppConfig.Context.oneMillionWindow, vision = true),
                model("gemini-2.0-flash-lite", AppConfig.Context.oneMillionWindow, vision = true),
                reasoningModel("gemini-2.5-pro", AppConfig.Context.oneMillionWindow, vision = true),
                reasoningModel("gemini-2.5-flash", AppConfig.Context.oneMillionWindow, vision = true),
            ),
        ),
        BuiltInModelProvider(
            id = "xai",
            displayName = "xAI / Grok",
            models = listOf(
                model("grok-2", AppConfig.Context.defaultContextWindow),
                model("grok-2-vision", AppConfig.ModelCatalog.context32k, vision = true),
                model("grok-3", AppConfig.ModelCatalog.context131k),
                reasoningModel("grok-3-mini", AppConfig.ModelCatalog.context131k),
            ),
        ),
        BuiltInModelProvider(
            id = "zhipu",
            displayName = "智谱 / GLM",
            models = listOf(
                model("glm-4", AppConfig.Context.defaultContextWindow),
                model("glm-4-plus", AppConfig.Context.defaultContextWindow),
                model("glm-4-flash", AppConfig.Context.defaultContextWindow),
                model("glm-4v", AppConfig.ModelCatalog.context32k, vision = true),
            ),
        ),
        BuiltInModelProvider(
            id = "bytedance",
            displayName = "豆包 / Doubao",
            models = listOf(
                model("doubao-pro-32k", AppConfig.ModelCatalog.context32k),
                model("doubao-pro-128k", AppConfig.Context.defaultContextWindow),
                model("doubao-lite-32k", AppConfig.ModelCatalog.context32k),
                model("doubao-vision-pro", AppConfig.Context.defaultContextWindow, vision = true),
            ),
        ),
    )

    private val exactCapabilities: Map<String, BuiltInModelCapability> =
        providers.flatMap { it.models }.associateBy { it.id.lowercase() }

    fun capabilityFor(modelId: String): BuiltInModelCapability? {
        val normalized = modelId.trim().lowercase()
        if (normalized.isBlank()) return null
        exactCapabilities[normalized]?.let { return it.copy(id = modelId.trim()) }

        return when {
            normalized.contains("qwen-long") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.qwenLongContext)
            normalized.contains("gemini-1.5-pro") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.gemini15ProContext, vision = true)
            normalized.contains("gemini-2.5") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.oneMillionWindow, vision = true, reasoning = true)
            normalized.contains("gemini") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.oneMillionWindow, vision = true)
            normalized.contains("gpt-5") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.oneMillionWindow, vision = true, reasoning = true)
            normalized.contains("gpt-4.1") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.openAiGpt41Context, vision = true)
            normalized.contains("gpt-4o") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.defaultContextWindow, vision = true)
            normalized == "o3" || normalized.startsWith("o3-") || normalized.startsWith("o4-") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.context200k, vision = !normalized.contains("mini"), reasoning = true)
            normalized.contains("claude") ->
                inferred(
                    modelId,
                    defaultContextWindow = AppConfig.ModelCatalog.context200k,
                    vision = true,
                    oneMillion = normalized.contains("sonnet-4") || normalized.contains("opus-4"),
                    reasoning = normalized.contains("sonnet-4") || normalized.contains("opus-4"),
                )
            normalized.contains("deepseek") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.context64k, reasoning = normalized.contains("reasoner"))
            normalized.contains("qwen") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.defaultContextWindow, vision = normalized.contains("vl"))
            normalized.contains("grok-3") ->
                inferred(modelId, defaultContextWindow = AppConfig.ModelCatalog.context131k, vision = normalized.contains("vision"), reasoning = normalized.contains("mini"))
            normalized.contains("grok") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.defaultContextWindow, vision = normalized.contains("vision"))
            normalized.contains("glm") ->
                inferred(modelId, defaultContextWindow = AppConfig.Context.defaultContextWindow, vision = normalized.contains("4v"))
            normalized.contains("doubao") ->
                inferred(
                    modelId,
                    defaultContextWindow = if (normalized.contains("32k")) {
                        AppConfig.ModelCatalog.context32k
                    } else {
                        AppConfig.Context.defaultContextWindow
                    },
                    vision = normalized.contains("vision"),
                )
            else -> null
        }
    }

    private fun model(
        id: String,
        contextWindow: Int,
        vision: Boolean = false,
        oneMillion: Boolean = false,
    ): BuiltInModelCapability =
        BuiltInModelCapability(
            id = id,
            displayName = id,
            defaultContextWindow = contextWindow,
            supportsVision = vision,
            supports1mContext = oneMillion || contextWindow >= AppConfig.Context.oneMillionWindow,
        )

    private fun reasoningModel(
        id: String,
        contextWindow: Int,
        vision: Boolean = false,
        oneMillion: Boolean = false,
    ): BuiltInModelCapability =
        model(id, contextWindow, vision, oneMillion).copy(reasoningEfforts = DefaultReasoningEfforts)

    private fun inferred(
        id: String,
        defaultContextWindow: Int,
        vision: Boolean = false,
        oneMillion: Boolean = false,
        reasoning: Boolean = false,
    ): BuiltInModelCapability =
        BuiltInModelCapability(
            id = id.trim(),
            displayName = id.trim(),
            defaultContextWindow = defaultContextWindow,
            supportsVision = vision,
            supports1mContext = oneMillion || defaultContextWindow >= AppConfig.Context.oneMillionWindow,
            reasoningEfforts = if (reasoning) DefaultReasoningEfforts else emptyList(),
        )

}
