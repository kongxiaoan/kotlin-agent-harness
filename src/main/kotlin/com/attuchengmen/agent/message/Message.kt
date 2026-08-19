package com.attuchengmen.agent.message

import com.attuchengmen.agent.model.ToolCall

/**
 * 阅读顺序 1：发送给语言模型的上下文成员。
 *
 * `Message` 不是运行事实源，而是从 Session 事件中生成的模型视图。
 * 使用封闭类型集合可以避免用 `role: String` 表示角色时产生非法值。
 */
sealed interface Message

/** 模型历史中的用户消息。 */
data class UserMessage(
    val content: String,
) : Message

/** 模型历史中的 Assistant 消息。 */
data class AssistantMessage(
    val content: String,
) : Message

/** 模型历史中的 Assistant 工具请求。 */
data class ToolCallMessage(
    val call: ToolCall,
    val content: String? = null,
) : Message

/** 与指定模型工具请求配对的执行结果。 */
data class ToolResultMessage(
    val callId: String,
    val content: String,
    val isError: Boolean,
) : Message
