package com.attuchengmen.agent.tool

import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 读取工作区内受大小限制的 UTF-8 文件。
 *
 * 构造时解析真实工作区路径；每次执行再次解析目标真实路径，拒绝绝对路径、
 * 目录穿越和指向工作区外部的符号链接。本工具不是操作系统级文件沙箱。
 */
class ReadFileTool(
    workspaceRoot: Path,
    private val maxBytes: Int,
) : Tool {
    override val definition = ToolDefinition(
        name = TOOL_NAME,
        description = "Read a UTF-8 text file inside the current workspace.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Path relative to the workspace root.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
            put("additionalProperties", false)
        },
    )

    init {
        require(maxBytes in 1 until Int.MAX_VALUE) { "maxBytes must be between 1 and ${Int.MAX_VALUE - 1}" }
    }

    private val workspaceRoot = workspaceRoot.toRealPath()

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val input = decodeArguments(arguments)
        val target = resolveTarget(input.path)
        val bytes = readBounded(target)
        return try {
            UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw ToolExecutionException(TOOL_NAME, "file is not valid UTF-8", error)
        }
    }

    private fun decodeArguments(arguments: String): ReadFileArguments {
        val input = try {
            json.decodeFromString<ReadFileArguments>(arguments)
        } catch (error: SerializationException) {
            throw ToolArgumentsException(TOOL_NAME, "expected JSON object with string field \"path\"", error)
        }
        if (input.path.isBlank()) throw ToolArgumentsException(TOOL_NAME, "path must not be blank")
        return input
    }

    private fun resolveTarget(path: String): Path {
        val relative = try {
            Path.of(path)
        } catch (error: InvalidPathException) {
            throw ToolArgumentsException(TOOL_NAME, "path is invalid", error)
        }
        if (relative.isAbsolute) throw ToolArgumentsException(TOOL_NAME, "path must be relative to the workspace")

        val candidate = workspaceRoot.resolve(relative).normalize()
        if (!candidate.startsWith(workspaceRoot)) {
            throw ToolArgumentsException(TOOL_NAME, "path escapes the workspace")
        }
        val target = try {
            candidate.toRealPath()
        } catch (error: IOException) {
            throw ToolExecutionException(TOOL_NAME, "file cannot be opened", error)
        }
        if (!target.startsWith(workspaceRoot)) {
            throw ToolArgumentsException(TOOL_NAME, "path escapes the workspace")
        }
        if (!Files.isRegularFile(target)) throw ToolExecutionException(TOOL_NAME, "path is not a regular file")
        return target
    }

    private fun readBounded(path: Path): ByteArray {
        val bytes = try {
            Files.newInputStream(path).use { input -> input.readNBytes(maxBytes + 1) }
        } catch (error: IOException) {
            throw ToolExecutionException(TOOL_NAME, "file cannot be read", error)
        }
        if (bytes.size > maxBytes) {
            throw ToolExecutionException(TOOL_NAME, "file exceeds $maxBytes byte limit")
        }
        return bytes
    }

    private companion object {
        private const val TOOL_NAME = "read_file"
        private val json = Json { ignoreUnknownKeys = false }
    }
}

@Serializable
private data class ReadFileArguments(
    val path: String,
)
