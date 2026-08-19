package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ModelRetryPolicy
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSeekAdapterTest {
    @Test
    fun `stream ending without done is a retryable transport failure`() = withServer(
        responseStatus = 200,
        responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
    ) { server, _ ->
        val failure = assertFailsWith<DeepSeekTransportException> {
            runBlocking {
                DeepSeekAdapter(config(server))
                    .stream(ModelRequest(listOf(UserMessage("hello")), emptyList()))
                    .toList()
            }
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `streaming tool call deltas preserve raw json arguments`() = withServer(
        responseStatus = 200,
        responseBody = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\"\"}}]}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"README.md\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
            "",
            "",
        ).joinToString("\n"),
    ) { server, received ->
        val tool = ToolDefinition(
            name = "read_file",
            description = "Read a file.",
            parameters = buildJsonObject { put("type", "object") },
        )

        val chunks = runBlocking {
            DeepSeekAdapter(config(server))
                .stream(ModelRequest(listOf(UserMessage("hello")), listOf(tool)))
                .toList()
        }

        assertEquals(
            listOf(
                ModelChunk.ToolCallDelta(0, "call-1", "read_file", "{\"path\""),
                ModelChunk.ToolCallDelta(0, "call-1", "read_file", ":\"README.md\"}"),
                ModelChunk.Finished(
                    ModelResponse.ToolRequest(
                        ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"),
                    ),
                ),
            ),
            chunks,
        )
        assertTrue(received.body.contains("\"stream\":true"))
    }

    @Test
    fun `streams text deltas and assembles the terminal answer`() = withServer(
        responseStatus = 200,
        responseBody = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hel\"},\"finish_reason\":null}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
            "",
        ).joinToString("\n"),
    ) { server, received ->
        val chunks = runBlocking {
            DeepSeekAdapter(config(server))
                .stream(ModelRequest(listOf(UserMessage("hello")), emptyList()))
                .toList()
        }

        assertEquals(
            listOf(
                ModelChunk.TextDelta("hel"),
                ModelChunk.TextDelta("lo"),
                ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("hello"))),
            ),
            chunks,
        )
        assertTrue(received.body.contains("\"stream\":true"))
    }

    @Test
    fun `http failure classification only retries transient statuses`() {
        assertTrue(DeepSeekHttpException(429, "busy").retryable)
        assertTrue(DeepSeekHttpException(503, "unavailable").retryable)
        assertFalse(DeepSeekHttpException(401, "unauthorized").retryable)
    }

    @Test
    fun `maps runtime request to DeepSeek protocol`() = withServer(
        responseStatus = 200,
        responseBody = """{"choices":[{"message":{"role":"assistant","content":"done"}}]}""",
    ) { server, received ->
        val call = ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}")
        val tool = ToolDefinition(
            name = "read_file",
            description = "Read a file.",
            parameters = buildJsonObject { put("type", "object") },
        )
        val adapter = DeepSeekAdapter(config(server))

        val response = runBlocking { adapter.generate(
            ModelRequest(
                messages = listOf(
                    UserMessage("read it"),
                    ToolCallMessage(call, content = "I will read it."),
                    ToolResultMessage("call-1", "permission denied", isError = true),
                ),
                tools = listOf(tool),
            ),
        ) }

        assertEquals(ModelResponse.Answer(AssistantMessage("done")), response)
        assertEquals("Bearer test-key", received.authorization)
        val body = Json.parseToJsonElement(received.body).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        assertEquals("user", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("I will read it.", messages[1].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("Error: permission denied", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("read_file", body.getValue("tools").jsonArray[0].jsonObject
            .getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `maps one DeepSeek tool call to runtime response`() = withServer(
        responseStatus = 200,
        responseBody = """{"choices":[{"message":{"role":"assistant","content":"Let me read it.","tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}]}""",
    ) { server, _ ->
        val adapter = DeepSeekAdapter(config(server))

        val response = runBlocking {
            adapter.generate(ModelRequest(listOf(UserMessage("read it")), emptyList()))
        }

        assertEquals(
            ModelResponse.ToolRequest(
                call = ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"),
                content = "Let me read it.",
            ),
            response,
        )
    }

    @Test
    fun `non-success response fails with status without exposing api key`() = withServer(
        responseStatus = 401,
        responseBody = """{"error":{"message":"invalid credentials"}}""",
    ) { server, _ ->
        val failure = assertFailsWith<DeepSeekHttpException> {
            runBlocking {
                DeepSeekAdapter(config(server)).generate(ModelRequest(listOf(UserMessage("hello")), emptyList()))
            }
        }

        assertEquals(401, failure.statusCode)
        assertEquals(false, failure.message.orEmpty().contains("test-key"))
    }

    private fun config(server: HttpServer) = DeepSeekConfig(
        apiKey = "test-key",
        model = "test-model",
        baseUri = URI("http://127.0.0.1:${server.address.port}"),
        connectTimeout = Duration.ofSeconds(2),
        requestTimeout = Duration.ofSeconds(2),
        retryPolicy = ModelRetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(4)),
    )

    private fun withServer(
        responseStatus: Int,
        responseBody: String,
        block: (HttpServer, ReceivedRequest) -> Unit,
    ) {
        val received = ReceivedRequest()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received.authorization = exchange.requestHeaders.getFirst("Authorization")
            received.body = exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(server, received)
        } finally {
            server.stop(0)
        }
    }
}

private class ReceivedRequest {
    var authorization: String? = null
    var body: String = ""
}
