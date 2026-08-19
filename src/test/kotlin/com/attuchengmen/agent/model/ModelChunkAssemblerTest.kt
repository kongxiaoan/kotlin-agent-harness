package com.attuchengmen.agent.model

import com.attuchengmen.agent.message.AssistantMessage
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelChunkAssemblerTest {
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
