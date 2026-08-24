package com.attuchengmen.agent.context

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import com.attuchengmen.agent.session.AssistantMessageAdded
import com.attuchengmen.agent.session.SessionEvent
import com.attuchengmen.agent.session.SessionEventEnvelope
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import com.attuchengmen.agent.session.StepStarted
import com.attuchengmen.agent.session.ToolCallRequested
import com.attuchengmen.agent.session.ToolResultAdded
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import com.attuchengmen.agent.session.TurnStarted
import com.attuchengmen.agent.session.UserMessageAdded
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContextManagerTest {
    @Test
    fun `keeps the current turn and the newest complete turns within budget`() {
        val events = envelopes(
            TurnStarted(1),
            UserMessageAdded("aaa"),
            AssistantMessageAdded("bbb"),
            TurnEnded(1, TurnOutcome.Completed),
            TurnStarted(2),
            UserMessageAdded("cccc"),
            AssistantMessageAdded("dddd"),
            TurnEnded(2, TurnOutcome.Completed),
            TurnStarted(3),
            StepStarted(3, 1),
            UserMessageAdded("ee"),
        )
        val manager = ContextManager(
            window = ContextWindow(contextWindowTokens = 14, maxOutputTokens = 2),
            tokenEstimator = MessageLengthTokenEstimator,
        )

        val plan = manager.build(events, currentTurn = 3, tools = emptyList())

        assertEquals(
            listOf(UserMessage("cccc"), AssistantMessage("dddd"), UserMessage("ee")),
            plan.request.messages,
        )
        assertEquals(listOf(SessionEventRange(5, 8), SessionEventRange(9, 11)), plan.selectedEventRanges)
        assertEquals(10, plan.estimatedInputTokens)
        assertEquals(12, plan.inputTokenBudget)
        assertEquals(2, plan.request.maxOutputTokens)
    }

    @Test
    fun `history selection is a contiguous recent turn suffix`() {
        val events = envelopes(
            TurnStarted(1),
            UserMessageAdded("a"),
            AssistantMessageAdded("b"),
            TurnEnded(1, TurnOutcome.Completed),
            TurnStarted(2),
            UserMessageAdded("123456789"),
            AssistantMessageAdded("123456789"),
            TurnEnded(2, TurnOutcome.Completed),
            TurnStarted(3),
            StepStarted(3, 1),
            UserMessageAdded("x"),
        )
        val manager = ContextManager(
            window = ContextWindow(contextWindowTokens = 12, maxOutputTokens = 2),
            tokenEstimator = MessageLengthTokenEstimator,
        )

        val plan = manager.build(events, currentTurn = 3, tools = emptyList())

        assertEquals(listOf(UserMessage("x")), plan.request.messages)
        assertEquals(listOf(SessionEventRange(9, 11)), plan.selectedEventRanges)
    }

    @Test
    fun `tool call and result are selected as part of one complete turn`() {
        val call = ToolCall("call-1", "read_file", "{}")
        val events = envelopes(
            TurnStarted(1),
            UserMessageAdded("read"),
            ToolCallRequested(1, 1, call),
            ToolResultAdded(1, 1, call.id, "content", isError = false),
            AssistantMessageAdded("done"),
            TurnEnded(1, TurnOutcome.Completed),
            TurnStarted(2),
            StepStarted(2, 1),
            UserMessageAdded("next"),
        )
        val manager = ContextManager(
            window = ContextWindow(contextWindowTokens = 100, maxOutputTokens = 10),
            tokenEstimator = MessageLengthTokenEstimator,
        )

        val plan = manager.build(events, currentTurn = 2, tools = emptyList())

        assertEquals(
            listOf(
                UserMessage("read"),
                ToolCallMessage(call),
                ToolResultMessage(call.id, "content", isError = false),
                AssistantMessage("done"),
                UserMessage("next"),
            ),
            plan.request.messages,
        )
        assertEquals(listOf(SessionEventRange(1, 6), SessionEventRange(7, 9)), plan.selectedEventRanges)
    }

    @Test
    fun `current turn exceeding input budget fails instead of truncating it`() {
        val events = envelopes(
            TurnStarted(1),
            StepStarted(1, 1),
            UserMessageAdded("12345"),
        )
        val manager = ContextManager(
            window = ContextWindow(contextWindowTokens = 6, maxOutputTokens = 2),
            tokenEstimator = MessageLengthTokenEstimator,
        )

        val failure = assertFailsWith<ContextWindowExceededException> {
            manager.build(events, currentTurn = 1, tools = emptyList())
        }

        assertEquals(5, failure.requiredInputTokens)
        assertEquals(4, failure.inputTokenBudget)
    }

    @Test
    fun `tool definitions consume the same input budget as messages`() {
        val events = envelopes(
            TurnStarted(1),
            StepStarted(1, 1),
            UserMessageAdded("1234"),
        )
        val manager = ContextManager(
            window = ContextWindow(contextWindowTokens = 8, maxOutputTokens = 2),
            tokenEstimator = object : InputTokenEstimator {
                override val id = "test-tools"

                override fun estimate(request: ModelRequest): Int =
                    MessageLengthTokenEstimator.estimate(request) + request.tools.size * 3
            },
        )

        assertFailsWith<ContextWindowExceededException> {
            manager.build(
                events,
                currentTurn = 1,
                tools = listOf(testToolDefinition()),
            )
        }
    }

    private fun envelopes(vararg events: SessionEvent): List<SessionEventEnvelope> =
        events.mapIndexed { index, event ->
            SessionEventEnvelope(
                sessionId = SessionId("session-1"),
                sequence = index + 1L,
                occurredAt = Instant.parse("2026-08-24T08:00:00Z"),
                event = event,
            )
        }

    private fun testToolDefinition(): ToolDefinition = ToolDefinition(
        name = "read_file",
        description = "Read a file.",
        parameters = buildJsonObject { put("type", "object") },
    )
}

private object MessageLengthTokenEstimator : InputTokenEstimator {
    override val id = "test-message-length"

    override fun estimate(request: ModelRequest): Int = request.messages.sumOf(::messageLength)

    private fun messageLength(message: Message): Int = when (message) {
        is UserMessage -> message.content.length
        is AssistantMessage -> message.content.length
        is ToolCallMessage -> message.call.arguments.length + message.content.orEmpty().length
        is ToolResultMessage -> message.content.length
    }
}
