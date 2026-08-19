package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelRequestException
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ModelRetryPolicy
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** DeepSeek HTTP、模型选择和超时配置；API Key 只由 Adapter 使用。 */
data class DeepSeekConfig(
    val apiKey: String,
    val model: String,
    val baseUri: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val retryPolicy: ModelRetryPolicy,
) {
    init {
        require(apiKey.isNotBlank()) { "DeepSeek apiKey must not be blank" }
        require(model.isNotBlank()) { "DeepSeek model must not be blank" }
        require(connectTimeout > Duration.ZERO) { "DeepSeek connectTimeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "DeepSeek requestTimeout must be positive" }
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

/**
 * DeepSeek Chat Completion Adapter。
 *
 * 当前显式关闭 thinking，并只接受一个工具调用；Runtime 只依赖
 * [LanguageModel]，不感知 DeepSeek 的鉴权、URL 或 JSON DTO。
 */
class DeepSeekAdapter(
    private val config: DeepSeekConfig,
) : LanguageModel {
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

        val text = StringBuilder()
        var toolCall: StreamingToolCall? = null
        response.body().bufferedReader(UTF_8).use { reader ->
            val events = DeepSeekSseReader(reader)
            while (true) {
                currentCoroutineContext().ensureActive()
                val payload = try {
                    events.nextData()
                } catch (error: IOException) {
                    throw DeepSeekTransportException(error)
                } ?: throw DeepSeekTransportException(IOException("SSE stream ended without [DONE]"))
                if (payload == "[DONE]") {
                    val pendingCall = toolCall
                    if (pendingCall != null) {
                        val id = pendingCall.id
                            ?: throw DeepSeekProtocolException("streaming tool call has no id")
                        val name = pendingCall.name
                            ?: throw DeepSeekProtocolException("streaming tool call has no function name")
                        emit(
                            ModelChunk.Finished(
                                ModelResponse.ToolRequest(
                                    call = ToolCall(id, name, pendingCall.arguments.toString()),
                                    content = text.toString().takeIf { it.isNotEmpty() },
                                ),
                            ),
                        )
                        return@flow
                    }
                    if (text.isEmpty()) throw ModelRequestException(
                        "DeepSeek stream produced no content",
                        retryable = true,
                    )
                    emit(ModelChunk.Finished(ModelResponse.Answer(AssistantMessage(text.toString()))))
                    return@flow
                }
                val delta = decodeStreamDelta(payload) ?: continue
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
    }.flowOn(Dispatchers.IO)

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

    private fun decodeStreamDelta(payload: String): DeepSeekDelta? {
        val response = try {
            json.decodeFromString<DeepSeekStreamResponse>(payload)
        } catch (error: SerializationException) {
            throw DeepSeekProtocolException("SSE data is not valid JSON", error)
        }
        if (response.choices.isEmpty()) return null
        if (response.choices.size != 1) {
            throw DeepSeekProtocolException("expected one streaming choice, found ${response.choices.size}")
        }
        return response.choices.single().delta
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
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}

private fun toDeepSeekMessage(message: Message): JsonObject = when (message) {
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
)

@Serializable
private data class DeepSeekStreamChoice(
    val delta: DeepSeekDelta,
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

private data class StreamingToolCall(
    val index: Int,
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
)

/** 按 SSE 空行边界读取 `data:` 字段；网络分片由 BufferedReader 处理。 */
private class DeepSeekSseReader(
    private val reader: BufferedReader,
) {
    fun nextData(): String? {
        val data = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) {
                if (data.isNotEmpty()) return data.joinToString("\n")
                continue
            }
            if (line.startsWith(":")) continue
            if (line == "data") {
                data.add("")
            } else if (line.startsWith("data:")) {
                data.add(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
}
