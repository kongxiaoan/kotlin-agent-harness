package com.attuchengmen.agent.session

import com.attuchengmen.agent.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRecoveryTest {
    @Test
    fun `balanced log needs no recovery events`() {
        val events = listOf(
            TurnStarted(turn = 1),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
        )

        assertEquals(emptyList(), SessionRecovery.interruptedTurnClosers(events))
    }

    @Test
    fun `interrupted tool execution is closed before its step and turn`() {
        val events = listOf(
            TurnStarted(turn = 2),
            StepStarted(turn = 2, step = 1),
            ToolCallRequested(
                turn = 2,
                step = 1,
                call = ToolCall("call-1", "write_file", "{\"path\":\"result.txt\"}"),
            ),
        )

        assertEquals(
            listOf(
                ToolResultAdded(
                    turn = 2,
                    step = 1,
                    callId = "call-1",
                    content = SessionRecovery.TOOL_OUTCOME_UNKNOWN_MESSAGE,
                    isError = true,
                ),
                StepEnded(turn = 2, step = 1),
                TurnEnded(turn = 2, outcome = TurnOutcome.Interrupted),
            ),
            SessionRecovery.interruptedTurnClosers(events),
        )
    }

    @Test
    fun `answered tool call is not given a second result`() {
        val events = listOf(
            TurnStarted(turn = 1),
            StepStarted(turn = 1, step = 1),
            ToolCallRequested(turn = 1, step = 1, call = ToolCall("call-1", "read_file", "{}")),
            ToolResultAdded(turn = 1, step = 1, callId = "call-1", content = "ok", isError = false),
        )

        assertEquals(
            listOf(
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.Interrupted),
            ),
            SessionRecovery.interruptedTurnClosers(events),
        )
    }
}
