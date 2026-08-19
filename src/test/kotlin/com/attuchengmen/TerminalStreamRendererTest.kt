package com.attuchengmen

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.session.ModelChunkReceived
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalStreamRendererTest {
    @Test
    fun `streamed final answer is not printed twice`() {
        val output = ByteArrayOutputStream()
        val renderer = TerminalStreamRenderer(PrintStream(output))

        renderer.onEvent(ModelChunkReceived(1, 1, 1, ModelChunk.TextDelta("hel")))
        renderer.onEvent(ModelChunkReceived(1, 1, 1, ModelChunk.TextDelta("lo")))
        renderer.finish(AssistantMessage("hello"))

        assertEquals("hello\n", output.toString())
    }

    @Test
    fun `non-streamed final answer follows text from an earlier tool step`() {
        val output = ByteArrayOutputStream()
        val renderer = TerminalStreamRenderer(PrintStream(output))

        renderer.onEvent(ModelChunkReceived(1, 1, 1, ModelChunk.TextDelta("checking")))
        renderer.finish(AssistantMessage("done"))

        assertEquals("checking\ndone\n", output.toString())
    }
}
