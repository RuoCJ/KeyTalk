package com.keytalk.app.network

import com.keytalk.app.config.AppConfig
import com.keytalk.app.domain.model.ProtocolAdapter
import com.keytalk.app.provider.ChatError
import com.keytalk.app.provider.ChatErrorType
import com.keytalk.app.provider.ChatRequest
import com.keytalk.app.provider.ChatResponse
import com.keytalk.app.provider.ProviderAdapter
import com.keytalk.app.provider.ProviderAdapterRegistry
import com.keytalk.app.provider.ProviderException
import com.keytalk.app.provider.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OkHttpChatNetworkClient(
    private val adapterRegistry: ProviderAdapterRegistry = ProviderAdapterRegistry.default(),
    private val okHttpClient: OkHttpClient = defaultClient(),
) : ChatNetworkClient {
    private val activeCalls = ConcurrentHashMap<String, Call>()

    constructor(
        adapter: ProviderAdapter,
        okHttpClient: OkHttpClient = defaultClient(),
    ) : this(ProviderAdapterRegistry.single(adapter), okHttpClient)

    override suspend fun send(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val adapter = adapterRegistry.adapterFor(request.protocolAdapter)
        val httpRequest = adapter.buildRequest(request.copy(stream = false))
        val call = okHttpClient.newCall(httpRequest)
        activeCalls[request.requestId] = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(adapter.mapHttpError(response.code, body))
                }
                adapter.parseNonStreamingResponse(request.requestId, body)
            }
        } catch (e: SocketTimeoutException) {
            throw ProviderException(
                ChatError(ChatErrorType.NETWORK_TIMEOUT, "网络请求超时，请稍后重试。", retryable = true),
            )
        } finally {
            activeCalls.remove(request.requestId)
        }
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val adapter = adapterRegistry.adapterFor(request.protocolAdapter)
        val streamingRequest = request.copy(stream = true)
        val httpRequest = adapter.buildRequest(streamingRequest)
        val call = okHttpClient.newCall(httpRequest)
        activeCalls[request.requestId] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    emit(StreamEvent.Error(request.requestId, adapter.mapHttpError(response.code, body)))
                    return@use
                }

                val source = response.body?.source()
                if (source == null) {
                    emit(
                        StreamEvent.Error(
                            request.requestId,
                            ChatError(ChatErrorType.INVALID_RESPONSE, "服务商响应为空。"),
                        ),
                    )
                    return@use
                }

                val decoder = SseDecoder()
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    val sseEvent = decoder.accept(line)
                    if (sseEvent != null) {
                        adapter.parseStreamingEvent(request.requestId, sseEvent)?.let { emit(it) }
                    }
                }
                decoder.finish()?.let { sseEvent ->
                    adapter.parseStreamingEvent(request.requestId, sseEvent)?.let { emit(it) }
                }
            }
        } catch (e: SocketTimeoutException) {
            emit(
                StreamEvent.Error(
                    request.requestId,
                    ChatError(ChatErrorType.NETWORK_TIMEOUT, "流式响应超时。", retryable = true),
                ),
            )
        } catch (e: IOException) {
            if (currentCoroutineContext().isActive) {
                emit(
                    StreamEvent.Error(
                        request.requestId,
                        ChatError(ChatErrorType.STREAM_INTERRUPTED, "流式连接已中断。", retryable = true),
                    ),
                )
            } else {
                throw e
            }
        } finally {
            activeCalls.remove(request.requestId)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(
        protocolAdapter: ProtocolAdapter,
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): List<String> = withContext(Dispatchers.IO) {
        when (protocolAdapter) {
            ProtocolAdapter.OPENAI_COMPATIBLE,
            ProtocolAdapter.CUSTOM,
            ProtocolAdapter.GROK_NATIVE,
            -> listOpenAICompatibleModels(baseUrl, apiKey, allowInsecureHttp)

            ProtocolAdapter.CLAUDE_NATIVE -> listClaudeModels(baseUrl, apiKey, allowInsecureHttp)
            ProtocolAdapter.GEMINI_NATIVE -> listGeminiModels(baseUrl, apiKey, allowInsecureHttp)
        }
    }

    private fun listOpenAICompatibleModels(
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): List<String> {
        val endpointUrl = runCatching { openAICompatibleModelsUrl(baseUrl).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !allowInsecureHttp) {
            throw ProviderException(
                ChatError(ChatErrorType.INVALID_BASE_URL, "默认只允许 HTTPS Base URL。"),
            )
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(mapModelListHttpError(response.code, body, "获取模型列表"))
                }
                parseOpenAICompatibleModelIds(body)
            }
        } catch (e: SocketTimeoutException) {
            throw ProviderException(
                ChatError(ChatErrorType.NETWORK_TIMEOUT, "获取模型列表超时，请稍后重试。", retryable = true),
            )
        }
    }

    private fun listClaudeModels(
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): List<String> {
        val endpointUrl = runCatching { claudeModelsUrl(baseUrl).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Claude Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !allowInsecureHttp) {
            throw ProviderException(
                ChatError(ChatErrorType.INVALID_BASE_URL, "Claude Native 默认只允许 HTTPS Base URL。"),
            )
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .get()
            .header("x-api-key", apiKey)
            .header("anthropic-version", AppConfig.Provider.anthropicVersion)
            .header("Accept", "application/json")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(mapModelListHttpError(response.code, body, "获取 Claude 模型列表"))
                }
                parseClaudeModelIds(body)
            }
        } catch (e: SocketTimeoutException) {
            throw ProviderException(
                ChatError(ChatErrorType.NETWORK_TIMEOUT, "获取 Claude 模型列表超时，请稍后重试。", retryable = true),
            )
        }
    }

    private fun listGeminiModels(
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): List<String> {
        val endpointUrl = runCatching { geminiModelsUrl(baseUrl).toHttpUrl() }
            .getOrElse {
                throw ProviderException(
                    ChatError(ChatErrorType.INVALID_BASE_URL, "Gemini Base URL 无效，请检查连接配置。"),
                )
            }
        if (endpointUrl.scheme != "https" && !allowInsecureHttp) {
            throw ProviderException(
                ChatError(ChatErrorType.INVALID_BASE_URL, "Gemini Native 默认只允许 HTTPS Base URL。"),
            )
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .get()
            .header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(mapModelListHttpError(response.code, body, "获取 Gemini 模型列表"))
                }
                parseGeminiModelIds(body)
            }
        } catch (e: SocketTimeoutException) {
            throw ProviderException(
                ChatError(ChatErrorType.NETWORK_TIMEOUT, "获取 Gemini 模型列表超时，请稍后重试。", retryable = true),
            )
        }
    }

    private fun openAICompatibleModelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/models" else "$trimmed/v1/models"
    }

    private fun claudeModelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/models" else "$trimmed/v1/models"
    }

    private fun geminiModelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val versioned = if (trimmed.endsWith("/v1") || trimmed.endsWith("/v1beta")) trimmed else "$trimmed/v1beta"
        return "$versioned/models"
    }

    override fun cancel(requestId: String) {
        activeCalls.remove(requestId)?.cancel()
    }

    private fun parseOpenAICompatibleModelIds(body: String): List<String> {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }.distinct()
    }

    private fun parseClaudeModelIds(body: String): List<String> {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }.distinct()
    }

    private fun parseGeminiModelIds(body: String): List<String> {
        val root = Json.parseToJsonElement(body).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { item ->
            val model = item.jsonObject
            val methods = model["supportedGenerationMethods"]?.jsonArray?.mapNotNull { method ->
                method.jsonPrimitive.contentOrNull
            }
            if (
                methods != null &&
                methods.none { it == "generateContent" || it == "streamGenerateContent" }
            ) {
                return@mapNotNull null
            }
            model["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
        }.distinct()
    }

    private fun mapModelListHttpError(statusCode: Int, body: String, operation: String): ChatError =
        when (statusCode) {
            401 -> ChatError(ChatErrorType.INVALID_API_KEY, "API Key 无效或未授权。", httpStatusCode = statusCode)
            403 -> ChatError(ChatErrorType.PERMISSION_DENIED, "当前 API Key 没有获取模型列表的权限。", httpStatusCode = statusCode)
            404 -> ChatError(
                ChatErrorType.INVALID_BASE_URL,
                "未找到模型列表接口，请确认 Base URL 是否应以 /v1 结尾。",
                httpStatusCode = statusCode,
            )
            else -> ChatError(
                ChatErrorType.INVALID_RESPONSE,
                "$operation 失败：HTTP $statusCode",
                httpStatusCode = statusCode,
                sanitizedRawResponse = sanitizeRawResponse(body),
            )
        }

    private fun sanitizeRawResponse(body: String?): String? =
        body
            ?.replace(
                Regex(
                    "(?i)(authorization|x-api-key|x-goog-api-key|api[_-]?key|key)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,}&]+",
                ),
                "$1: <redacted>",
            )
            ?.take(AppConfig.Network.sanitizedResponseMaxChars)

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(AppConfig.Network.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(AppConfig.Network.readTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(AppConfig.Network.callTimeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}
