package com.attuchengmen.agent.tool

import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ToolRegistryTest {
    @Test
    fun `registered tool receives raw model arguments`() {
        val tool = StubTool("read_file", "content")
        val registry = ToolRegistry(listOf(tool))

        val result = runBlocking {
            registry.execute(ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"))
        }

        assertEquals("content", result)
        assertEquals(listOf("{\"path\":\"README.md\"}"), tool.arguments)
    }

    @Test
    fun `unknown tool fails explicitly`() {
        val registry = ToolRegistry()

        val failure = assertFailsWith<UnknownToolException> {
            runBlocking { registry.execute(ToolCall("call-1", "missing", "{}")) }
        }

        assertEquals("unknown tool \"missing\"", failure.message)
    }

    @Test
    fun `duplicate tool name is rejected`() {
        val registry = ToolRegistry(listOf(StubTool("read_file", "first")))

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.register(StubTool("read_file", "second"))
        }

        assertEquals("tool \"read_file\" is already registered", failure.message)
    }

    @Test
    fun `definitions preserve registration order`() {
        val first = StubTool("read_file", "first")
        val second = StubTool("search_text", "second")
        val registry = ToolRegistry(listOf(first, second))

        assertEquals(listOf(first.definition, second.definition), registry.definitions)
    }
}

private class StubTool(
    name: String,
    private val result: String,
) : Tool {
    override val definition = ToolDefinition(
        name = name,
        description = "test tool",
        parameters = buildJsonObject { put("type", "object") },
    )
    val arguments = mutableListOf<String>()

    override suspend fun execute(arguments: String): String {
        this.arguments.add(arguments)
        return result
    }
}
