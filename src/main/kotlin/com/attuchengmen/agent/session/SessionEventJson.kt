package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration

/** JSON 文件边界使用的 SessionEvent 编解码器。 */
internal object SessionEventJson {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(event: SessionEvent): String =
        json.encodeToString<StoredSessionEvent>(event.toStored())

    fun decode(value: String): SessionEvent =
        json.decodeFromString<StoredSessionEvent>(value).toDomain()
}

@Serializable
private sealed interface StoredSessionEvent

@Serializable
@SerialName("user-message-added")
private data class StoredUserMessageAdded(val content: String) : StoredSessionEvent

@Serializable
@SerialName("assistant-message-added")
private data class StoredAssistantMessageAdded(val content: String) : StoredSessionEvent

@Serializable
@SerialName("tool-call-requested")
private data class StoredToolCallRequested(
    val turn: Int,
    val step: Int,
    val call: StoredToolCall,
    val content: String? = null,
) : StoredSessionEvent

@Serializable
@SerialName("tool-result-added")
private data class StoredToolResultAdded(
    val turn: Int,
    val step: Int,
    val callId: String,
    val content: String,
    val isError: Boolean,
) : StoredSessionEvent

@Serializable
@SerialName("model-request-prepared")
private data class StoredModelRequestPrepared(
    val turn: Int,
    val step: Int,
    val tools: List<StoredToolDefinition>,
    val attempt: Int = 1,
) : StoredSessionEvent

@Serializable
@SerialName("model-retry-scheduled")
private data class StoredModelRetryScheduled(
    val turn: Int,
    val step: Int,
    val retry: Int,
    val delayMillis: Long,
    val failure: String,
) : StoredSessionEvent

@Serializable
@SerialName("model-chunk-received")
private data class StoredModelChunkReceived(
    val turn: Int,
    val step: Int,
    val attempt: Int,
    val chunk: StoredModelChunk,
) : StoredSessionEvent

@Serializable
private sealed interface StoredModelChunk

@Serializable
@SerialName("text-delta")
private data class StoredTextDelta(val text: String) : StoredModelChunk

@Serializable
@SerialName("finished")
private data class StoredFinished(val response: StoredModelResponse) : StoredModelChunk

@Serializable
private sealed interface StoredModelResponse

@Serializable
@SerialName("answer")
private data class StoredAnswer(val content: String) : StoredModelResponse

@Serializable
@SerialName("tool-request")
private data class StoredToolRequest(
    val call: StoredToolCall,
    val content: String? = null,
) : StoredModelResponse

@Serializable
private data class StoredToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class StoredToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
@SerialName("turn-started")
private data class StoredTurnStarted(val turn: Int) : StoredSessionEvent

@Serializable
@SerialName("step-started")
private data class StoredStepStarted(val turn: Int, val step: Int) : StoredSessionEvent

@Serializable
@SerialName("step-ended")
private data class StoredStepEnded(val turn: Int, val step: Int) : StoredSessionEvent

@Serializable
@SerialName("turn-ended")
private data class StoredTurnEnded(
    val turn: Int,
    val outcome: StoredTurnOutcome,
) : StoredSessionEvent

@Serializable
private sealed interface StoredTurnOutcome

@Serializable
@SerialName("completed")
private data object StoredCompleted : StoredTurnOutcome

@Serializable
@SerialName("cancelled")
private data object StoredCancelled : StoredTurnOutcome

@Serializable
@SerialName("interrupted")
private data object StoredInterrupted : StoredTurnOutcome

@Serializable
@SerialName("timed-out")
private data class StoredTimedOut(val timeoutMillis: Long) : StoredTurnOutcome

@Serializable
@SerialName("failed")
private data class StoredFailed(val message: String) : StoredTurnOutcome

private fun SessionEvent.toStored(): StoredSessionEvent = when (this) {
    is UserMessageAdded -> StoredUserMessageAdded(content)
    is AssistantMessageAdded -> StoredAssistantMessageAdded(content)
    is ToolCallRequested -> StoredToolCallRequested(
        turn = turn,
        step = step,
        call = StoredToolCall(call.id, call.name, call.arguments),
        content = content,
    )
    is ToolResultAdded -> StoredToolResultAdded(turn, step, callId, content, isError)
    is ModelRequestPrepared -> StoredModelRequestPrepared(
        turn = turn,
        step = step,
        tools = tools.map { StoredToolDefinition(it.name, it.description, it.parameters) },
        attempt = attempt,
    )
    is ModelRetryScheduled -> StoredModelRetryScheduled(turn, step, retry, delayMillis, failure)
    is ModelChunkReceived -> StoredModelChunkReceived(turn, step, attempt, chunk.toStored())
    is TurnStarted -> StoredTurnStarted(turn)
    is StepStarted -> StoredStepStarted(turn, step)
    is StepEnded -> StoredStepEnded(turn, step)
    is TurnEnded -> StoredTurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            TurnOutcome.Completed -> StoredCompleted
            TurnOutcome.Cancelled -> StoredCancelled
            TurnOutcome.Interrupted -> StoredInterrupted
            is TurnOutcome.TimedOut -> StoredTimedOut(value.timeout.toMillis())
            is TurnOutcome.Failed -> StoredFailed(value.message)
        },
    )
}

private fun StoredSessionEvent.toDomain(): SessionEvent = when (this) {
    is StoredUserMessageAdded -> UserMessageAdded(content)
    is StoredAssistantMessageAdded -> AssistantMessageAdded(content)
    is StoredToolCallRequested -> ToolCallRequested(
        turn = turn,
        step = step,
        call = ToolCall(call.id, call.name, call.arguments),
        content = content,
    )
    is StoredToolResultAdded -> ToolResultAdded(turn, step, callId, content, isError)
    is StoredModelRequestPrepared -> ModelRequestPrepared(
        turn = turn,
        step = step,
        tools = tools.map { ToolDefinition(it.name, it.description, it.parameters) },
        attempt = attempt,
    )
    is StoredModelRetryScheduled -> ModelRetryScheduled(turn, step, retry, delayMillis, failure)
    is StoredModelChunkReceived -> ModelChunkReceived(turn, step, attempt, chunk.toDomain())
    is StoredTurnStarted -> TurnStarted(turn)
    is StoredStepStarted -> StepStarted(turn, step)
    is StoredStepEnded -> StepEnded(turn, step)
    is StoredTurnEnded -> TurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            StoredCompleted -> TurnOutcome.Completed
            StoredCancelled -> TurnOutcome.Cancelled
            StoredInterrupted -> TurnOutcome.Interrupted
            is StoredTimedOut -> TurnOutcome.TimedOut(Duration.ofMillis(value.timeoutMillis))
            is StoredFailed -> TurnOutcome.Failed(value.message)
        },
    )
}

private fun ModelChunk.toStored(): StoredModelChunk = when (this) {
    is ModelChunk.TextDelta -> StoredTextDelta(text)
    is ModelChunk.Finished -> StoredFinished(response.toStored())
}

private fun StoredModelChunk.toDomain(): ModelChunk = when (this) {
    is StoredTextDelta -> ModelChunk.TextDelta(text)
    is StoredFinished -> ModelChunk.Finished(response.toDomain())
}

private fun ModelResponse.toStored(): StoredModelResponse = when (this) {
    is ModelResponse.Answer -> StoredAnswer(message.content)
    is ModelResponse.ToolRequest -> StoredToolRequest(
        call = StoredToolCall(call.id, call.name, call.arguments),
        content = content,
    )
}

private fun StoredModelResponse.toDomain(): ModelResponse = when (this) {
    is StoredAnswer -> ModelResponse.Answer(AssistantMessage(content))
    is StoredToolRequest -> ModelResponse.ToolRequest(
        call = ToolCall(call.id, call.name, call.arguments),
        content = content,
    )
}
