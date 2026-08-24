package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.Message
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.memory.MemoryContextFormatter

/**
 * 阅读顺序 4：把 Session 事实投影为下一次模型调用需要的消息。
 *
 * 投影是无状态纯转换：不修改输入，不读取全局状态，相同事件序列产生
 * 相同消息序列。生命周期事件仍保留在日志中，但不会污染模型上下文。
 */
object SessionProjector {
    /**
     * 按事件原顺序生成模型消息。
     * 封闭事件集合保证新增事件后必须在这里作出显式处理决定。
     */
    fun toMessages(events: List<SessionEvent>): List<Message> =
        events.mapNotNull { event ->
            when (event) {
                is UserMessageAdded -> UserMessage(event.content)
                is AssistantMessageAdded -> AssistantMessage(event.content)
                is ToolCallRequested -> ToolCallMessage(event.call, event.content)
                is ToolResultAdded -> ToolResultMessage(event.callId, event.content, event.isError)
                is ContextPrepared -> null
                is ModelRequestPrepared -> null
                is ModelRetryScheduled -> null
                is ModelChunkReceived -> null
                is TurnStarted -> null
                is StepStarted -> null
                is StepEnded -> null
                is TurnEnded -> null
            }
        }

    /** 根据请求边界之前的消息事实和已记录工具定义重建一次模型请求。 */
    fun toRequest(events: List<SessionEvent>, turn: Int, step: Int, attempt: Int = 1): ModelRequest {
        val matches = events.withIndex().filter { (_, event) ->
            event is ModelRequestPrepared && event.turn == turn && event.step == step && event.attempt == attempt
        }
        require(matches.size == 1) {
            "expected exactly one model request for turn $turn step $step attempt $attempt, found ${matches.size}"
        }
        val boundary = matches.single()
        val event = boundary.value as ModelRequestPrepared
        val contextMatches = events.subList(0, boundary.index).filterIsInstance<ContextPrepared>().filter {
            it.turn == turn && it.step == step && it.attempt == attempt
        }
        require(contextMatches.size <= 1) {
            "expected at most one context selection for turn $turn step $step attempt $attempt"
        }
        val context = contextMatches.singleOrNull()
        val messageEvents = context?.let { selected ->
            require(selected.selectedEventRanges.zipWithNext().all { (left, right) ->
                left.toSequence < right.fromSequence
            }) { "context event ranges must be ordered and non-overlapping" }
            selected.selectedEventRanges.flatMap { range ->
                require(range.toSequence < boundary.index + 1L) { "context selection must precede its model request" }
                events.subList((range.fromSequence - 1).toInt(), range.toSequence.toInt())
            }
        } ?: events.subList(0, boundary.index)
        return ModelRequest(
            messages = buildList {
                if (context != null && context.selectedMemories.isNotEmpty()) {
                    add(MemoryContextFormatter.toMessage(context.selectedMemories))
                }
                addAll(toMessages(messageEvents))
            },
            tools = event.tools.toList(),
            maxOutputTokens = event.maxOutputTokens,
        )
    }
}
