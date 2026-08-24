package com.attuchengmen.agent.context

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.SystemMessage
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ToolDefinition
import com.attuchengmen.agent.memory.MemoryContextEntry
import com.attuchengmen.agent.memory.MemoryContextFormatter
import com.attuchengmen.agent.memory.MemoryRecord
import com.attuchengmen.agent.session.SessionEventEnvelope
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionProjector
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnStarted
import java.nio.charset.StandardCharsets.UTF_8

/** 模型总窗口、输出预留与运行时安全余量。 */
data class ContextWindow(
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val safetyMarginTokens: Int = 0,
) {
    init {
        require(contextWindowTokens > 0) { "contextWindowTokens must be positive" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(safetyMarginTokens >= 0) { "safetyMarginTokens must not be negative" }
        require(maxOutputTokens.toLong() + safetyMarginTokens < contextWindowTokens) {
            "output reservation and safety margin must leave a positive input budget"
        }
    }

    // 输入令牌预算
    val inputTokenBudget: Int
        get() = contextWindowTokens - maxOutputTokens - safetyMarginTokens
}

/** Provider 请求在发送前使用的 Token 估算能力。 */
interface InputTokenEstimator {
    val id: String

    fun estimate(request: ModelRequest): Int
}

/** 当前 Turn 本身无法完整放入模型输入。 */
class ContextWindowExceededException(
    val requiredInputTokens: Int,
    val inputTokenBudget: Int,
) : IllegalStateException(
    "current turn requires $requiredInputTokens estimated input tokens, budget is $inputTokenBudget",
)

/** 一次模型调用已经完成容量选择的 Context。 */
data class ContextPlan(
    val request: ModelRequest,
    val selectedEventRanges: List<SessionEventRange>,
    val selectedMemories: List<MemoryContextEntry>,
    val estimatedInputTokens: Int,
    val inputTokenBudget: Int,
    val tokenEstimatorId: String,
)

/**
 * 从候选 Memory 与 Session 事实中构建受 Token Budget 约束的模型请求。
 *
 * 当前 Turn 永不裁剪；Memory 逐条适配；历史从近到远加入，首个超限 Turn 终止选择。
 */
class ContextManager(
    private val window: ContextWindow,
    private val tokenEstimator: InputTokenEstimator,
) {
    fun build(
        envelopes: List<SessionEventEnvelope>,
        currentTurn: Int,
        tools: List<ToolDefinition>,
        memories: List<MemoryRecord> = emptyList(),
    ): ContextPlan {
        require(currentTurn > 0) { "currentTurn must be positive" }
        require(envelopes.isNotEmpty()) { "context requires session events" }
        require(tokenEstimator.id.isNotBlank()) { "token estimator id must not be blank" }

        val segments = turnSegments(envelopes, currentTurn)
        val current = segments.last()
        var selected = listOf(current)
        var selectedMemories = emptyList<MemoryContextEntry>()
        var request = request(envelopes, selected, tools, selectedMemories)
        var estimatedTokens = estimate(request)
        if (estimatedTokens > window.inputTokenBudget) {
            throw ContextWindowExceededException(estimatedTokens, window.inputTokenBudget)
        }

        for (memory in memories) {
            val snapshot = MemoryContextEntry(memory.id, memory.kind, memory.content, memory.version)
            val candidateMemories = selectedMemories + snapshot
            val candidateRequest = request(envelopes, selected, tools, candidateMemories)
            val candidateTokens = estimate(candidateRequest)
            if (candidateTokens <= window.inputTokenBudget) {
                selectedMemories = candidateMemories
                request = candidateRequest
                estimatedTokens = candidateTokens
            }
        }

        for (history in segments.dropLast(1).asReversed()) {
            val candidate = listOf(history) + selected
            val candidateRequest = request(envelopes, candidate, tools, selectedMemories)
            val candidateTokens = estimate(candidateRequest)
            if (candidateTokens > window.inputTokenBudget) break
            selected = candidate
            request = candidateRequest
            estimatedTokens = candidateTokens
        }

        return ContextPlan(
            request = request,
            selectedEventRanges = selected.map { it.toEventRange(envelopes) },
            selectedMemories = selectedMemories,
            estimatedInputTokens = estimatedTokens,
            inputTokenBudget = window.inputTokenBudget,
            tokenEstimatorId = tokenEstimator.id,
        )
    }

    private fun turnSegments(
        envelopes: List<SessionEventEnvelope>,
        currentTurn: Int,
    ): List<IndexRange> {
        val starts = envelopes.mapIndexedNotNull { index, envelope ->
            (envelope.event as? TurnStarted)?.let { index to it.turn }
        }
        require(starts.count { it.second == currentTurn } == 1) {
            "expected exactly one start for current turn $currentTurn"
        }
        val currentStartPosition = starts.indexOfFirst { it.second == currentTurn }
        require(currentStartPosition == starts.lastIndex) { "current turn must be the latest started turn" }
        require(envelopes.drop(starts.last().first).none { envelope ->
            val ended = envelope.event as? TurnEnded
            ended?.turn == currentTurn
        }) { "current turn $currentTurn has already ended" }

        val completed = starts.dropLast(1).mapIndexed { position, (startIndex, turn) ->
            val nextStartIndex = starts[position + 1].first
            val endIndexes = (startIndex until nextStartIndex).filter { index ->
                val ended = envelopes[index].event as? TurnEnded
                ended?.turn == turn
            }
            require(endIndexes.size == 1) { "historical turn $turn must have exactly one terminal event" }
            IndexRange(startIndex, endIndexes.single())
        }
        return completed + IndexRange(starts.last().first, envelopes.lastIndex)
    }

    private fun request(
        envelopes: List<SessionEventEnvelope>,
        selected: List<IndexRange>,
        tools: List<ToolDefinition>,
        memories: List<MemoryContextEntry>,
    ): ModelRequest {
        val events = selected.flatMap { range ->
            envelopes.subList(range.fromIndex, range.toIndex + 1).map(SessionEventEnvelope::event)
        }
        return ModelRequest(
            messages = buildList {
                if (memories.isNotEmpty()) add(MemoryContextFormatter.toMessage(memories))
                addAll(SessionProjector.toMessages(events))
            },
            tools = tools.toList(),
            maxOutputTokens = window.maxOutputTokens,
        )
    }

    private fun estimate(request: ModelRequest): Int {
        val value = tokenEstimator.estimate(request)
        require(value >= 0) { "token estimator returned a negative value" }
        return value
    }

    private data class IndexRange(
        val fromIndex: Int,
        val toIndex: Int,
    ) {
        fun toEventRange(envelopes: List<SessionEventEnvelope>): SessionEventRange = SessionEventRange(
            fromSequence = envelopes[fromIndex].sequence,
            toSequence = envelopes[toIndex].sequence,
        )
    }
}

/**
 * 以每个 UTF-8 字节一个 Token 并加入协议余量的保守估算器。
 *
 * 它用于缺少官方本地 Tokenizer 的部署；结果不是 Provider 的精确计费值。
 */
object ConservativeUtf8TokenEstimator : InputTokenEstimator {
    override val id = "utf8-byte-conservative-v1"

    override fun estimate(request: ModelRequest): Int {
        var total = REQUEST_OVERHEAD.toLong()
        for (message in request.messages) {
            total += MESSAGE_OVERHEAD + message.estimatedBytes()
        }
        for (tool in request.tools) {
            total += TOOL_OVERHEAD
            total += tool.name.utf8Bytes() + tool.description.utf8Bytes() + tool.parameters.toString().utf8Bytes()
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun Message.estimatedBytes(): Int = when (this) {
        is SystemMessage -> content.utf8Bytes()
        is UserMessage -> content.utf8Bytes()
        is AssistantMessage -> content.utf8Bytes()
        is ToolCallMessage -> call.id.utf8Bytes() + call.name.utf8Bytes() +
                call.arguments.utf8Bytes() + content.orEmpty().utf8Bytes()

        is ToolResultMessage -> callId.utf8Bytes() + content.utf8Bytes() + 1
    }

    private fun String.utf8Bytes(): Int = toByteArray(UTF_8).size

    private const val REQUEST_OVERHEAD = 16
    private const val MESSAGE_OVERHEAD = 16
    private const val TOOL_OVERHEAD = 32
}
