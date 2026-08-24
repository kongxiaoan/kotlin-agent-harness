package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.memory.MemoryContextEntry
import com.attuchengmen.agent.memory.MemoryId
import com.attuchengmen.agent.memory.MemoryKind
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelPricing
import com.attuchengmen.agent.model.ModelProfile
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.TokenUsage
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
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
            ContextPrepared(
                turn = 1,
                step = 2,
                attempt = 1,
                selectedEventRanges = listOf(SessionEventRange(1, 4), SessionEventRange(8, 9)),
                estimatedInputTokens = 200,
                inputTokenBudget = 800,
                tokenEstimatorId = "utf8-byte-conservative-v1",
                selectedMemories = listOf(
                    MemoryContextEntry(
                        id = MemoryId("memory-1"),
                        kind = MemoryKind.SEMANTIC,
                        content = "The user writes Kotlin.",
                        version = 2,
                    ),
                ),
            ),
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
                maxOutputTokens = 384,
                profile = ModelProfile(
                    provider = "deepseek",
                    model = "deepseek-v4-flash",
                    pricing = ModelPricing(
                        version = "2026-08-cny",
                        currency = "CNY",
                        inputPerMillion = BigDecimal("1.00"),
                        cacheReadPerMillion = BigDecimal("0.02"),
                        cacheWritePerMillion = BigDecimal.ZERO,
                        outputPerMillion = BigDecimal("2.00"),
                    ),
                ),
            ),
            ModelRetryScheduled(1, 2, retry = 1, delayMillis = 500, failure = "busy"),
            ModelChunkReceived(1, 2, attempt = 1, chunk = ModelChunk.TextDelta("hi")),
            ModelChunkReceived(
                1,
                2,
                attempt = 1,
                chunk = ModelChunk.ToolCallDelta(0, "call-1", "read_file", "{\"path\":"),
            ),
            ModelChunkReceived(
                1,
                2,
                attempt = 1,
                chunk = ModelChunk.Usage(
                    TokenUsage(100, 20, cacheReadTokens = 80, reasoningTokens = 5),
                ),
                observedAt = Instant.parse("2026-08-19T10:15:30Z"),
            ),
            ModelChunkReceived(
                1,
                2,
                attempt = 1,
                chunk = ModelChunk.Finished(
                    ModelResponse.Answer(AssistantMessage("hi")),
                    ModelFinishReason.MAX_TOKENS,
                ),
            ),
            TurnStarted(turn = 1),
            StepStarted(turn = 1, step = 2),
            StepEnded(turn = 1, step = 2),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
            TurnEnded(turn = 6, outcome = TurnOutcome.MaxTokens),
            TurnEnded(turn = 2, outcome = TurnOutcome.Cancelled),
            TurnEnded(turn = 3, outcome = TurnOutcome.Interrupted),
            TurnEnded(turn = 4, outcome = TurnOutcome.TimedOut(Duration.ofSeconds(30))),
            TurnEnded(turn = 5, outcome = TurnOutcome.Failed("model unavailable")),
        )

        val sessionId = SessionId("session-1")
        val occurredAt = Instant.parse("2026-08-20T08:00:00Z")
        for ((index, event) in events.withIndex()) {
            val envelope = SessionEventEnvelope(sessionId, index + 1L, occurredAt, event)
            assertEquals(envelope, SessionEventJson.decode(SessionEventJson.encode(envelope)))
        }
    }
}
