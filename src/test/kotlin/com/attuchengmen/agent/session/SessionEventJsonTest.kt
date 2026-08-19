package com.attuchengmen.agent.session

import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionEventJsonTest {
    @Test
    fun `all session event variants round trip through json`() {
        val events = listOf(
            UserMessageAdded("hello"),
            AssistantMessageAdded("hi"),
            ToolCallRequested(
                turn = 1,
                step = 2,
                call = ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"),
                content = "I will read it.",
            ),
            ToolResultAdded(1, 2, "call-1", "project content", isError = false),
            ModelRequestPrepared(
                turn = 1,
                step = 2,
                tools = listOf(
                    ToolDefinition(
                        name = "read_file",
                        description = "Read a file.",
                        parameters = buildJsonObject { put("type", "object") },
                    ),
                ),
            ),
            TurnStarted(turn = 1),
            StepStarted(turn = 1, step = 2),
            StepEnded(turn = 1, step = 2),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
            TurnEnded(turn = 2, outcome = TurnOutcome.Cancelled),
            TurnEnded(turn = 3, outcome = TurnOutcome.TimedOut(Duration.ofSeconds(30))),
            TurnEnded(turn = 4, outcome = TurnOutcome.Failed("model unavailable")),
        )

        for (event in events) {
            assertEquals(event, SessionEventJson.decode(SessionEventJson.encode(event)))
        }
    }
}
