package com.attuchengmen.agent.model

import com.attuchengmen.agent.message.AssistantMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelChunkAssemblerTest {
    @Test
    fun `tool deltas assemble to the matching raw tool request`() {
        val assembler = ModelChunkAssembler()
        assembler.push(ModelChunk.ToolCallDelta(0, "call-1", "read_file", "{\"path\""))
        assembler.push(ModelChunk.ToolCallDelta(0, "call-1", "read_file", ":\"README.md\"}"))
        val response = ModelResponse.ToolRequest(
            ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"),
        )
        assembler.push(ModelChunk.Finished(response))

        assertEquals(response, assembler.finish())
    }

    @Test
    fun `second tool index is rejected until parallel calls are supported`() {
        val assembler = ModelChunkAssembler()
        assembler.push(ModelChunk.ToolCallDelta(0, "call-1", "first", "{}"))

        assertFailsWith<ModelStreamProtocolException> {
            assembler.push(ModelChunk.ToolCallDelta(1, "call-2", "second", "{}"))
        }
    }

    @Test
    fun `stream without finish is a retryable interrupted request`() {
        val assembler = ModelChunkAssembler()
        assembler.push(ModelChunk.TextDelta("partial"))

        val failure = assertFailsWith<ModelRequestException> { assembler.finish() }

        assertTrue(failure.retryable)
    }

    @Test
    fun `finished answer must match its text deltas`() {
        val assembler = ModelChunkAssembler()
        assembler.push(ModelChunk.TextDelta("partial"))

        assertFailsWith<ModelStreamProtocolException> {
            assembler.push(ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("different"))))
        }
    }
}
