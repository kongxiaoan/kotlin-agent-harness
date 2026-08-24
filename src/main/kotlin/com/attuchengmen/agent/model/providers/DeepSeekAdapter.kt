package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.SystemMessage
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelPricing
import com.attuchengmen.agent.model.ModelProfile
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelRequestException
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ModelRetryPolicy
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import com.attuchengmen.agent.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** DeepSeek HTTP、模型选择和超时配置；API Key 只由 Adapter 使用。 */
data class DeepSeekConfig(
    val apiKey: String,
    val model: String,
    val baseUri: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val streamIdleTimeout: Duration,
    val retryPolicy: ModelRetryPolicy,
    val pricing: ModelPricing? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "DeepSeek apiKey must not be blank" }
        require(model.isNotBlank()) { "DeepSeek model must not be blank" }
        require(connectTimeout > Duration.ZERO) { "DeepSeek connectTimeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "DeepSeek requestTimeout must be positive" }
        require(streamIdleTimeout >= Duration.ofMillis(1)) {
            "DeepSeek streamIdleTimeout must be at least one millisecond"
        }
        require(baseUri.isAbsolute) { "DeepSeek baseUri must be absolute" }
        require(baseUri.scheme == "https" || isLoopbackHttp(baseUri)) {
            "DeepSeek baseUri must use HTTPS except for loopback tests"
        }
    }
}

/** DeepSeek 返回非成功 HTTP 状态。响应摘要不包含请求或 API Key。 */
class DeepSeekHttpException(
    val statusCode: Int,
    responseBody: String,
) : ModelRequestException(
    "DeepSeek request failed with HTTP $statusCode: ${responseBody.take(512)}",
    retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
)

/** DeepSeek 响应不符合当前 Adapter 支持的协议。 */
class DeepSeekProtocolException(
    detail: String,
    cause: Throwable? = null,
) : ModelRequestException("invalid DeepSeek response: $detail", retryable = false, cause)

/** DeepSeek 请求在收到 HTTP 响应前失败。 */
class DeepSeekTransportException(
    cause: Throwable,
) : ModelRequestException("DeepSeek request transport failed", retryable = true, cause)

/** DeepSeek SSE 在指定时间内没有收到任何字节。 */
class DeepSeekStreamIdleTimeoutException(
    val timeout: Duration,
) : ModelRequestException(
    "DeepSeek stream received no data for $timeout",
    retryable = true,
)

/**
 * DeepSeek Chat Completion Adapter。
 *
 * 当前显式关闭 thinking，并只接受一个工具调用；Runtime 只依赖
 * [LanguageModel]，不感知 DeepSeek 的鉴权、URL 或 JSON DTO。
 */
class DeepSeekAdapter(
    private val config: DeepSeekConfig,
) : LanguageModel {
    override val profile: ModelProfile = ModelProfile("deepseek", config.model, config.pricing)
    override val retryPolicy: ModelRetryPolicy = config.retryPolicy
    private val endpoint = URI(config.baseUri.toString().trimEnd('/') + "/chat/completions")
    private val client = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build()

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val httpRequest = createRequest(request, stream = false)
        val response = try {
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(UTF_8)).await()
        } catch (error: IOException) {
            throw DeepSeekTransportException(error)
        }
        if (response.statusCode() !in 200..299) {
            throw DeepSeekHttpException(response.statusCode(), response.body())
        }
        return decodeResponse(response.body())
    }

    override fun stream(request: ModelRequest): Flow<ModelChunk> {
        return streamSse(request)
    }

    /** 将 DeepSeek 文本和单工具调用增量转换为 Runtime chunk。 */
    private fun streamSse(request: ModelRequest): Flow<ModelChunk> = flow {
        val response = try {
            client.sendAsync(createRequest(request, stream = true), HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (error: IOException) {
            throw DeepSeekTransportException(error)
        }
        if (response.statusCode() !in 200..299) {
            val summary = response.body().bufferedReader(UTF_8).use { it.readText().take(512) }
            throw DeepSeekHttpException(response.statusCode(), summary)
        }

        val responseBody = response.body()
        val text = StringBuilder()
        var toolCall: StreamingToolCall? = null
        var pendingUsage: TokenUsage? = null
        var pendingFinishReason: ModelFinishReason? = null
        var pendingFinishFailure: DeepSeekProtocolException? = null
        responseBody.bufferedReader(UTF_8).use { reader ->
            val parser = DeepSeekSseParser()
            val buffer = CharArray(SSE_READ_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = try {
                    readWithIdleTimeout(reader, responseBody, buffer)
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw DeepSeekTransportException(error)
                }
                if (count < 0) {
                    throw DeepSeekTransportException(IOException("SSE stream ended without [DONE]"))
                }
                parser.feed(buffer, count)
                while (true) {
                    val payload = parser.pollData() ?: break
                    if (payload == "[DONE]") {
                        val pendingCall = toolCall
                        pendingUsage?.let { emit(ModelChunk.Usage(it)) }
                        pendingFinishFailure?.let { throw it }
                        if (pendingCall != null) {
                            val id = pendingCall.id
                                ?: throw DeepSeekProtocolException("streaming tool call has no id")
                            val name = pendingCall.name
                                ?: throw DeepSeekProtocolException("streaming tool call has no function name")
                            val reason = pendingFinishReason ?: ModelFinishReason.TOOL_CALLS
                            if (reason != ModelFinishReason.TOOL_CALLS) {
                                throw DeepSeekProtocolException("tool response ended with $reason")
                            }
                            emit(
                                ModelChunk.Finished(
                                    ModelResponse.ToolRequest(
                                        call = ToolCall(id, name, pendingCall.arguments.toString()),
                                        content = text.toString().takeIf { it.isNotEmpty() },
                                    ),
                                    reason,
                                ),
                            )
                            return@flow
                        }
                        val reason = pendingFinishReason ?: ModelFinishReason.STOP
                        if (reason == ModelFinishReason.TOOL_CALLS) {
                            throw DeepSeekProtocolException("text response ended with TOOL_CALLS")
                        }
                        if (text.isEmpty() && reason != ModelFinishReason.MAX_TOKENS) throw ModelRequestException(
                            "DeepSeek stream produced no content",
                            retryable = true,
                        )
                        emit(ModelChunk.Finished(ModelResponse.Answer(AssistantMessage(text.toString())), reason))
                        return@flow
                    }
                    val streamChunk = decodeStreamChunk(payload)
                    streamChunk.usage?.let { pendingUsage = mapUsage(it) }
                    val choice = streamChunk.choices.singleOrNull() ?: continue
                    choice.finishReason?.let {
                        try {
                            pendingFinishReason = mapFinishReason(it)
                        } catch (error: DeepSeekProtocolException) {
                            pendingFinishFailure = error
                        }
                    }
                    val delta = choice.delta
                    val content = delta.content
                    if (!content.isNullOrEmpty()) {
                        text.append(content)
                        emit(ModelChunk.TextDelta(content))
                    }
                    for (call in delta.toolCalls.orEmpty()) {
                        if (call.index < 0) throw DeepSeekProtocolException("tool call index must not be negative")
                        if (call.type != null && call.type != "function") {
                            throw DeepSeekProtocolException("unsupported streaming tool call type \"${call.type}\"")
                        }
                        val pending = toolCall ?: StreamingToolCall(call.index).also { toolCall = it }
                        if (pending.index != call.index) {
                            throw DeepSeekProtocolException("multiple streaming tool calls are not supported")
                        }
                        call.id?.let { pending.id = mergeStreamField("id", pending.id, it) }
                        call.function?.name?.let { pending.name = mergeStreamField("name", pending.name, it) }
                        val argumentsDelta = call.function?.arguments.orEmpty()
                        pending.arguments.append(argumentsDelta)
                        emit(
                            ModelChunk.ToolCallDelta(
                                index = call.index,
                                id = pending.id.orEmpty(),
                                name = pending.name,
                                argumentsDelta = argumentsDelta,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 关闭停滞的响应流，使阻塞读取能够结束并保留明确的失败原因。 */
    private suspend fun readWithIdleTimeout(
        reader: BufferedReader,
        responseBody: InputStream,
        buffer: CharArray,
    ): Int = coroutineScope {
        val timedOut = AtomicBoolean(false)
        val watchdog = launch(Dispatchers.IO) {
            delay(config.streamIdleTimeout.toMillis().milliseconds)
            timedOut.set(true)
            responseBody.close()
        }
        try {
            runInterruptible(Dispatchers.IO) { reader.read(buffer) }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (timedOut.get()) throw DeepSeekStreamIdleTimeoutException(config.streamIdleTimeout)
            throw error
        } finally {
            watchdog.cancel()
        }
    }

    private fun createRequest(request: ModelRequest, stream: Boolean): HttpRequest =
        HttpRequest.newBuilder(endpoint)
            .timeout(config.requestTimeout)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request, stream), UTF_8))
            .build()

    private fun encodeRequest(request: ModelRequest, stream: Boolean): String {
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", stream)
            request.maxOutputTokens?.let { put("max_tokens", it) }
            if (stream) putJsonObject("stream_options") { put("include_usage", true) }
            putJsonObject("thinking") { put("type", "disabled") }
            putJsonArray("messages") {
                request.messages.forEach { add(toDeepSeekMessage(it)) }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { add(toDeepSeekTool(it)) }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun decodeStreamChunk(payload: String): DeepSeekStreamResponse {
        val response = try {
            json.decodeFromString<DeepSeekStreamResponse>(payload)
        } catch (error: SerializationException) {
            throw DeepSeekProtocolException("SSE data is not valid JSON", error)
        }
        if (response.choices.size > 1) {
            throw DeepSeekProtocolException("expected one streaming choice, found ${response.choices.size}")
        }
        return response
    }

    private fun mapFinishReason(reason: String): ModelFinishReason = when (reason) {
        "stop" -> ModelFinishReason.STOP
        "tool_calls" -> ModelFinishReason.TOOL_CALLS
        "length" -> ModelFinishReason.MAX_TOKENS
        else -> throw DeepSeekProtocolException("unsupported finish reason \"$reason\"")
    }

    private fun mapUsage(usage: DeepSeekUsage): TokenUsage {
        try {
            require(usage.promptTokens >= 0) { "prompt_tokens must not be negative" }
            require(usage.completionTokens >= 0) { "completion_tokens must not be negative" }
            val detailHit = usage.promptTokensDetails?.cachedTokens
            val directHit = usage.promptCacheHitTokens
            require(detailHit == null || directHit == null || detailHit == directHit) {
                "cache hit fields disagree"
            }
            val reportedHit = directHit ?: detailHit
            val reportedMiss = usage.promptCacheMissTokens
            require(reportedHit == null || reportedHit in 0..usage.promptTokens) {
                "prompt_cache_hit_tokens exceeds prompt_tokens"
            }
            require(reportedMiss == null || reportedMiss in 0..usage.promptTokens) {
                "prompt_cache_miss_tokens exceeds prompt_tokens"
            }
            val cacheRead = reportedHit ?: reportedMiss?.let { usage.promptTokens - it }
            val input = usage.promptTokens - (cacheRead ?: 0)
            require(reportedMiss == null || reportedMiss == input) {
                "prompt cache hit and miss tokens do not sum to prompt_tokens"
            }
            usage.totalTokens?.let {
                require(it == Math.addExact(usage.promptTokens, usage.completionTokens)) {
                    "total_tokens does not equal prompt_tokens plus completion_tokens"
                }
            }
            return TokenUsage(
                inputTokens = input,
                outputTokens = usage.completionTokens,
                cacheReadTokens = cacheRead,
                reasoningTokens = usage.completionTokensDetails?.reasoningTokens,
            )
        } catch (error: IllegalArgumentException) {
            throw DeepSeekProtocolException("invalid token usage: ${error.message}", error)
        } catch (error: ArithmeticException) {
            throw DeepSeekProtocolException("invalid token usage: token total overflow", error)
        }
    }

    private fun mergeStreamField(field: String, previous: String?, next: String): String {
        if (previous != null && previous != next) {
            throw DeepSeekProtocolException("streaming tool call $field changed")
        }
        return next
    }

    private fun decodeResponse(body: String): ModelResponse {
        val response = try {
            json.decodeFromString<DeepSeekChatResponse>(body)
        } catch (error: SerializationException) {
            throw DeepSeekProtocolException("response is not valid JSON", error)
        }
        if (response.choices.size != 1) {
            throw DeepSeekProtocolException("expected one choice, found ${response.choices.size}")
        }
        val message = response.choices.single().message
        if (message.toolCalls.size > 1) {
            throw DeepSeekProtocolException("multiple tool calls are not supported")
        }
        val toolCall = message.toolCalls.singleOrNull()
        if (toolCall != null) {
            if (toolCall.type != "function") {
                throw DeepSeekProtocolException("unsupported tool call type \"${toolCall.type}\"")
            }
            return ModelResponse.ToolRequest(
                call = ToolCall(
                    id = toolCall.id,
                    name = toolCall.function.name,
                    arguments = toolCall.function.arguments,
                ),
                content = message.content,
            )
        }
        val content = message.content
            ?: throw DeepSeekProtocolException("assistant response has neither content nor tool call")
        return ModelResponse.Answer(AssistantMessage(content))
    }

    private companion object {
        private const val SSE_READ_BUFFER_SIZE = 1024
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}

private fun toDeepSeekMessage(message: Message): JsonObject = when (message) {
    is SystemMessage -> buildJsonObject {
        put("role", "system")
        put("content", message.content)
    }

    is UserMessage -> buildJsonObject {
        put("role", "user")
        put("content", message.content)
    }

    is AssistantMessage -> buildJsonObject {
        put("role", "assistant")
        put("content", message.content)
    }

    is ToolCallMessage -> buildJsonObject {
        put("role", "assistant")
        put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("tool_calls") {
            addJsonObject {
                put("id", message.call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", message.call.name)
                    put("arguments", message.call.arguments)
                }
            }
        }
    }

    is ToolResultMessage -> buildJsonObject {
        put("role", "tool")
        put("content", if (message.isError) "Error: ${message.content}" else message.content)
        put("tool_call_id", message.callId)
    }
}

private fun toDeepSeekTool(tool: ToolDefinition): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", tool.parameters)
    }
}

private fun isLoopbackHttp(uri: URI): Boolean =
    uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")

@Serializable
private data class DeepSeekChatResponse(
    val choices: List<DeepSeekChoice>,
)

@Serializable
private data class DeepSeekChoice(
    val message: DeepSeekAssistantResponse,
)

@Serializable
private data class DeepSeekAssistantResponse(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCall> = emptyList(),
)

@Serializable
private data class DeepSeekToolCall(
    val id: String,
    val type: String,
    val function: DeepSeekFunctionCall,
)

@Serializable
private data class DeepSeekFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
private data class DeepSeekStreamResponse(
    val choices: List<DeepSeekStreamChoice> = emptyList(),
    val usage: DeepSeekUsage? = null,
)

@Serializable
private data class DeepSeekStreamChoice(
    val delta: DeepSeekDelta = DeepSeekDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class DeepSeekDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekStreamToolCall>? = null,
)

@Serializable
private data class DeepSeekStreamToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: DeepSeekStreamFunction? = null,
)

@Serializable
private data class DeepSeekStreamFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class DeepSeekUsage(
    @SerialName("prompt_tokens") val promptTokens: Long,
    @SerialName("completion_tokens") val completionTokens: Long,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Long? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: DeepSeekPromptTokenDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: DeepSeekCompletionTokenDetails? = null,
)

@Serializable
private data class DeepSeekPromptTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Long? = null,
)

@Serializable
private data class DeepSeekCompletionTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)

private data class StreamingToolCall(
    val index: Int,
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
)

/** 跨网络分片解析 SSE，并只暴露完整的 `data` 事件。 */
private class DeepSeekSseParser {
    private val lineBuffer = StringBuilder()
    private val dataLines = mutableListOf<String>()
    private val events = ArrayDeque<String>()

    fun feed(buffer: CharArray, count: Int) {
        lineBuffer.append(buffer, 0, count)
        while (true) {
            val newline = lineBuffer.indexOf("\n")
            if (newline < 0) return
            val line = lineBuffer.substring(0, newline).removeSuffix("\r")
            lineBuffer.delete(0, newline + 1)
            accept(line)
        }
    }

    fun pollData(): String? = events.removeFirstOrNull()

    private fun accept(line: String) {
        if (line.isEmpty()) {
            if (dataLines.isNotEmpty()) {
                events.addLast(dataLines.joinToString("\n"))
                dataLines.clear()
            }
            return
        }
        if (line.startsWith(":")) return
        if (line == "data") {
            dataLines.add("")
        } else if (line.startsWith("data:")) {
            dataLines.add(line.removePrefix("data:").removePrefix(" "))
        }
    }
}
