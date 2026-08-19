package com.attuchengmen.agent.session

import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition

/**
 * 阅读顺序 2：Session 中按发生顺序记录的运行事实。
 *
 * 这是当前系统的事实源。新增事件时，`SessionProjector` 的穷尽 `when`
 * 会要求开发者明确决定该事件是否应该进入模型上下文。
 */
sealed interface SessionEvent

/** 用户内容已经被 Agent 接受并记录。 */
data class UserMessageAdded(
    val content: String,
) : SessionEvent

/** 模型回复已经成功产生并记录。 */
data class AssistantMessageAdded(
    val content: String,
) : SessionEvent

/** 模型产生了一个等待 Runtime 处理的工具请求。 */
data class ToolCallRequested(
    val turn: Int,
    val step: Int,
    val call: ToolCall,
    val content: String? = null,
) : SessionEvent

/** 与模型工具请求配对的成功或失败结果。 */
data class ToolResultAdded(
    val turn: Int,
    val step: Int,
    val callId: String,
    val content: String,
    val isError: Boolean,
) : SessionEvent

/**
 * 一次模型请求即将发送，并保存消息日志之外的模型可见工具定义。
 *
 * 该事件之前的消息事实与 [tools] 共同构成实际请求。
 */
data class ModelRequestPrepared(
    val turn: Int,
    val step: Int,
    val tools: List<ToolDefinition>,
) : SessionEvent

/**
 * 一个 Turn 已开始。
 *
 * 这是生命周期事实，不属于模型对话，因此投影时被明确忽略。
 */
data class TurnStarted(
    val turn: Int,
) : SessionEvent

/** 一次模型请求已在所属 Turn 中开始。 */
data class StepStarted(
    val turn: Int,
    val step: Int,
) : SessionEvent

/**
 * 一次已开始的模型请求已结束。
 *
 * 请求是否成功由模型消息和所属 Turn 的终态表达。
 */
data class StepEnded(
    val turn: Int,
    val step: Int,
) : SessionEvent

/** 一个已开始 Turn 的互斥终态。 */
sealed interface TurnOutcome {
    /** Turn 已正常产生模型回复。 */
    data object Completed : TurnOutcome

    /** Turn 因模型异常而结束。 */
    data class Failed(
        val message: String,
    ) : TurnOutcome
}

/**
 * 一个 Turn 已结束，与相同编号的 [TurnStarted] 配对。
 *
 * 终态属于运行事实，不进入模型上下文。
 */
data class TurnEnded(
    val turn: Int,
    val outcome: TurnOutcome,
) : SessionEvent
