package com.attuchengmen

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.session.ModelChunkReceived
import com.attuchengmen.agent.session.ModelRetryScheduled
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
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

    @Test
    fun `retry separates abandoned partial text from the successful attempt`() {
        val output = ByteArrayOutputStream()
        val renderer = TerminalStreamRenderer(PrintStream(output))

        renderer.onEvent(ModelChunkReceived(1, 1, 1, ModelChunk.TextDelta("par")))
        renderer.onEvent(ModelRetryScheduled(1, 1, retry = 1, delayMillis = 10, failure = "stream stalled"))
        renderer.onEvent(ModelChunkReceived(1, 1, 2, ModelChunk.TextDelta("done")))
        renderer.finish(AssistantMessage("done"))

        assertEquals("par\n[model retry 1]\ndone\n", output.toString())
    }

    @Test
    fun `max token outcome marks streamed text as truncated`() {
        val output = ByteArrayOutputStream()
        val renderer = TerminalStreamRenderer(PrintStream(output))

        renderer.onEvent(ModelChunkReceived(1, 1, 1, ModelChunk.TextDelta("partial")))
        renderer.onEvent(TurnEnded(1, TurnOutcome.MaxTokens))
        renderer.finish(AssistantMessage("partial"))

        assertEquals("partial\n[model output truncated: max tokens]\n", output.toString())
    }
}
