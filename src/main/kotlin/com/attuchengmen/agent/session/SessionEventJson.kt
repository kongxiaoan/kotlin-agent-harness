package com.attuchengmen.agent.session

import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
) : StoredSessionEvent

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
    )
    is TurnStarted -> StoredTurnStarted(turn)
    is StepStarted -> StoredStepStarted(turn, step)
    is StepEnded -> StoredStepEnded(turn, step)
    is TurnEnded -> StoredTurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            TurnOutcome.Completed -> StoredCompleted
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
    )
    is StoredTurnStarted -> TurnStarted(turn)
    is StoredStepStarted -> StepStarted(turn, step)
    is StoredStepEnded -> StepEnded(turn, step)
    is StoredTurnEnded -> TurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            StoredCompleted -> TurnOutcome.Completed
            is StoredFailed -> TurnOutcome.Failed(value.message)
        },
    )
}
