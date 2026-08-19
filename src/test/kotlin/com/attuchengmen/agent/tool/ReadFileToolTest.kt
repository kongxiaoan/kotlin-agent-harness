package com.attuchengmen.agent.tool

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ReadFileToolTest {
    @Test
    fun `definition exposes strict path parameter schema`() = withWorkspace { workspace ->
        val tool = ReadFileTool(workspace, maxBytes = 1024)

        assertEquals("read_file", tool.definition.name)
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string","description":"Path relative to the workspace root."}},"required":["path"],"additionalProperties":false}""",
            ),
            tool.definition.parameters,
        )
    }

    @Test
    fun `reads utf8 file inside workspace`() = withWorkspace { workspace ->
        workspace.resolve("README.md").writeText("Kotlin Agent Runtime")
        val tool = ReadFileTool(workspace, maxBytes = 1024)

        val result = runBlocking { tool.execute("{\"path\":\"README.md\"}") }

        assertEquals("Kotlin Agent Runtime", result)
    }

    @Test
    fun `rejects malformed or incomplete arguments`() = withWorkspace { workspace ->
        val tool = ReadFileTool(workspace, maxBytes = 1024)

        for (arguments in listOf(
            "not-json",
            "{}",
            "{\"path\":1}",
            "{\"path\":\"\"}",
            "{\"path\":\"README.md\",\"extra\":true}",
        )) {
            assertFailsWith<ToolArgumentsException> {
                runBlocking { tool.execute(arguments) }
            }
        }
    }

    @Test
    fun `rejects path outside workspace`() {
        val root = Files.createTempDirectory("read-file-tool-test")
        try {
            val workspace = Files.createDirectory(root.resolve("workspace"))
            root.resolve("secret.txt").writeText("secret")
            val tool = ReadFileTool(workspace, maxBytes = 1024)

            assertFailsWith<ToolArgumentsException> {
                runBlocking { tool.execute("{\"path\":\"../secret.txt\"}") }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects file larger than configured limit`() = withWorkspace { workspace ->
        workspace.resolve("large.txt").writeText("12345")
        val tool = ReadFileTool(workspace, maxBytes = 4)

        val failure = assertFailsWith<ToolExecutionException> {
            runBlocking { tool.execute("{\"path\":\"large.txt\"}") }
        }

        assertEquals("tool \"read_file\" failed: file exceeds 4 byte limit", failure.message)
    }

    private fun withWorkspace(block: (java.nio.file.Path) -> Unit) {
        val workspace = Files.createTempDirectory("read-file-tool-test")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}
