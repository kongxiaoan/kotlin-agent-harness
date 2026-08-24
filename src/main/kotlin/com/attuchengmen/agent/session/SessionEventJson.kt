package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelPricing
import com.attuchengmen.agent.model.ModelProfile
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.TokenUsage
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** JSON 文件边界使用的 SessionEventEnvelope 编解码器。 */
internal object SessionEventJson {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(envelope: SessionEventEnvelope): String =
        json.encodeToString(
            StoredSessionEventEnvelope(
                formatVersion = FORMAT_VERSION,
                sessionId = envelope.sessionId.value,
                sequence = envelope.sequence,
                occurredAt = envelope.occurredAt.toString(),
                event = envelope.event.toStored(),
            ),
        )

    fun decode(value: String): SessionEventEnvelope {
        val stored = json.decodeFromString<StoredSessionEventEnvelope>(value)
        if (stored.formatVersion != FORMAT_VERSION) {
            throw SerializationException("unsupported session format version ${stored.formatVersion}")
        }
        return SessionEventEnvelope(
            sessionId = SessionId(stored.sessionId),
            sequence = stored.sequence,
            occurredAt = Instant.parse(stored.occurredAt),
            event = stored.event.toDomain(),
        )
    }

    private const val FORMAT_VERSION = 1
}

@Serializable
private data class StoredSessionEventEnvelope(
    val formatVersion: Int,
    val sessionId: String,
    val sequence: Long,
    val occurredAt: String,
    val event: StoredSessionEvent,
)

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
@SerialName("context-prepared")
private data class StoredContextPrepared(
    val turn: Int,
    val step: Int,
    val attempt: Int,
    val selectedEventRanges: List<StoredSessionEventRange>,
    val estimatedInputTokens: Int,
    val inputTokenBudget: Int,
    val tokenEstimatorId: String,
) : StoredSessionEvent

@Serializable
private data class StoredSessionEventRange(
    val fromSequence: Long,
    val toSequence: Long,
)

@Serializable
@SerialName("model-request-prepared")
private data class StoredModelRequestPrepared(
    val turn: Int,
    val step: Int,
    val tools: List<StoredToolDefinition>,
    val attempt: Int = 1,
    val maxOutputTokens: Int? = null,
    val profile: StoredModelProfile? = null,
) : StoredSessionEvent

@Serializable
private data class StoredModelProfile(
    val provider: String,
    val model: String,
    val pricing: StoredModelPricing? = null,
)

@Serializable
private data class StoredModelPricing(
    val version: String,
    val currency: String,
    val inputPerMillion: String,
    val cacheReadPerMillion: String,
    val cacheWritePerMillion: String,
    val outputPerMillion: String,
)

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
    val observedAt: String? = null,
) : StoredSessionEvent

@Serializable
private sealed interface StoredModelChunk

@Serializable
@SerialName("text-delta")
private data class StoredTextDelta(val text: String) : StoredModelChunk

@Serializable
@SerialName("tool-call-delta")
private data class StoredToolCallDelta(
    val index: Int,
    val id: String,
    val name: String? = null,
    val argumentsDelta: String,
) : StoredModelChunk

@Serializable
@SerialName("finished")
private data class StoredFinished(
    val response: StoredModelResponse,
    val reason: StoredModelFinishReason? = null,
) : StoredModelChunk

@Serializable
@SerialName("usage")
private data class StoredUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
) : StoredModelChunk

@Serializable
private enum class StoredModelFinishReason {
    @SerialName("stop")
    STOP,

    @SerialName("tool-calls")
    TOOL_CALLS,

    @SerialName("max-tokens")
    MAX_TOKENS,
}

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
@SerialName("max-tokens")
private data object StoredMaxTokens : StoredTurnOutcome

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
    is ContextPrepared -> StoredContextPrepared(
        turn,
        step,
        attempt,
        selectedEventRanges.map { StoredSessionEventRange(it.fromSequence, it.toSequence) },
        estimatedInputTokens,
        inputTokenBudget,
        tokenEstimatorId,
    )
    is ModelRequestPrepared -> StoredModelRequestPrepared(
        turn = turn,
        step = step,
        tools = tools.map { StoredToolDefinition(it.name, it.description, it.parameters) },
        attempt = attempt,
        maxOutputTokens = maxOutputTokens,
        profile = profile?.toStored(),
    )
    is ModelRetryScheduled -> StoredModelRetryScheduled(turn, step, retry, delayMillis, failure)
    is ModelChunkReceived -> StoredModelChunkReceived(turn, step, attempt, chunk.toStored(), observedAt?.toString())
    is TurnStarted -> StoredTurnStarted(turn)
    is StepStarted -> StoredStepStarted(turn, step)
    is StepEnded -> StoredStepEnded(turn, step)
    is TurnEnded -> StoredTurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            TurnOutcome.Completed -> StoredCompleted
            TurnOutcome.MaxTokens -> StoredMaxTokens
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
    is StoredContextPrepared -> ContextPrepared(
        turn,
        step,
        attempt,
        selectedEventRanges.map { SessionEventRange(it.fromSequence, it.toSequence) },
        estimatedInputTokens,
        inputTokenBudget,
        tokenEstimatorId,
    )
    is StoredModelRequestPrepared -> ModelRequestPrepared(
        turn = turn,
        step = step,
        tools = tools.map { ToolDefinition(it.name, it.description, it.parameters) },
        attempt = attempt,
        maxOutputTokens = maxOutputTokens,
        profile = profile?.toDomain(),
    )
    is StoredModelRetryScheduled -> ModelRetryScheduled(turn, step, retry, delayMillis, failure)
    is StoredModelChunkReceived -> ModelChunkReceived(
        turn = turn,
        step = step,
        attempt = attempt,
        chunk = chunk.toDomain(),
        observedAt = observedAt?.let(Instant::parse),
    )
    is StoredTurnStarted -> TurnStarted(turn)
    is StoredStepStarted -> StepStarted(turn, step)
    is StoredStepEnded -> StepEnded(turn, step)
    is StoredTurnEnded -> TurnEnded(
        turn = turn,
        outcome = when (val value = outcome) {
            StoredCompleted -> TurnOutcome.Completed
            StoredMaxTokens -> TurnOutcome.MaxTokens
            StoredCancelled -> TurnOutcome.Cancelled
            StoredInterrupted -> TurnOutcome.Interrupted
            is StoredTimedOut -> TurnOutcome.TimedOut(Duration.ofMillis(value.timeoutMillis))
            is StoredFailed -> TurnOutcome.Failed(value.message)
        },
    )
}

private fun ModelChunk.toStored(): StoredModelChunk = when (this) {
    is ModelChunk.TextDelta -> StoredTextDelta(text)
    is ModelChunk.ToolCallDelta -> StoredToolCallDelta(index, id, name, argumentsDelta)
    is ModelChunk.Usage -> StoredUsage(
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cacheReadTokens = usage.cacheReadTokens,
        cacheWriteTokens = usage.cacheWriteTokens,
        reasoningTokens = usage.reasoningTokens,
    )
    is ModelChunk.Finished -> StoredFinished(response.toStored(), reason.toStored())
}

private fun StoredModelChunk.toDomain(): ModelChunk = when (this) {
    is StoredTextDelta -> ModelChunk.TextDelta(text)
    is StoredToolCallDelta -> ModelChunk.ToolCallDelta(index, id, name, argumentsDelta)
    is StoredUsage -> ModelChunk.Usage(
        TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens),
    )
    is StoredFinished -> if (reason == null) {
        ModelChunk.Finished(response.toDomain())
    } else {
        ModelChunk.Finished(response.toDomain(), reason.toDomain())
    }
}

private fun ModelProfile.toStored(): StoredModelProfile = StoredModelProfile(
    provider = provider,
    model = model,
    pricing = pricing?.let {
        StoredModelPricing(
            version = it.version,
            currency = it.currency,
            inputPerMillion = it.inputPerMillion.toPlainString(),
            cacheReadPerMillion = it.cacheReadPerMillion.toPlainString(),
            cacheWritePerMillion = it.cacheWritePerMillion.toPlainString(),
            outputPerMillion = it.outputPerMillion.toPlainString(),
        )
    },
)

private fun StoredModelProfile.toDomain(): ModelProfile = ModelProfile(
    provider = provider,
    model = model,
    pricing = pricing?.let {
        ModelPricing(
            version = it.version,
            currency = it.currency,
            inputPerMillion = BigDecimal(it.inputPerMillion),
            cacheReadPerMillion = BigDecimal(it.cacheReadPerMillion),
            cacheWritePerMillion = BigDecimal(it.cacheWritePerMillion),
            outputPerMillion = BigDecimal(it.outputPerMillion),
        )
    },
)

private fun ModelFinishReason.toStored(): StoredModelFinishReason = when (this) {
    ModelFinishReason.STOP -> StoredModelFinishReason.STOP
    ModelFinishReason.TOOL_CALLS -> StoredModelFinishReason.TOOL_CALLS
    ModelFinishReason.MAX_TOKENS -> StoredModelFinishReason.MAX_TOKENS
}

private fun StoredModelFinishReason.toDomain(): ModelFinishReason = when (this) {
    StoredModelFinishReason.STOP -> ModelFinishReason.STOP
    StoredModelFinishReason.TOOL_CALLS -> ModelFinishReason.TOOL_CALLS
    StoredModelFinishReason.MAX_TOKENS -> ModelFinishReason.MAX_TOKENS
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
