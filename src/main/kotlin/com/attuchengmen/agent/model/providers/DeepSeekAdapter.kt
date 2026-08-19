package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
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
import kotlinx.coroutines.future.await
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
) : IllegalStateException("DeepSeek request failed with HTTP $statusCode: ${responseBody.take(512)}")

/** DeepSeek 响应不符合当前 Adapter 支持的协议。 */
class DeepSeekProtocolException(
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("invalid DeepSeek response: $detail", cause)

/** DeepSeek 请求在收到 HTTP 响应前失败。 */
class DeepSeekTransportException(
    cause: Throwable,
) : IllegalStateException("DeepSeek request transport failed", cause)

/**
 * DeepSeek 非流式 Chat Completion Adapter。
 *
 * 当前显式关闭 thinking，并只接受一个工具调用；Runtime 只依赖
 * [LanguageModel]，不感知 DeepSeek 的鉴权、URL 或 JSON DTO。
 */
class DeepSeekAdapter(
    private val config: DeepSeekConfig,
) : LanguageModel {
    private val endpoint = URI(config.baseUri.toString().trimEnd('/') + "/chat/completions")
    private val client = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build()

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val httpRequest = HttpRequest.newBuilder(endpoint)
            .timeout(config.requestTimeout)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request), UTF_8))
            .build()
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

    private fun encodeRequest(request: ModelRequest): String {
        val body = buildJsonObject {
            put("model", config.model)
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
