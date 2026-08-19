package com.attuchengmen.agent.model

import com.attuchengmen.agent.message.AssistantMessage

/** 一次模型请求产生的互斥结果。 */
sealed interface ModelResponse {
    /** 模型已给出可直接返回给用户的答案。 */
    data class Answer(
        val message: AssistantMessage,
    ) : ModelResponse

    /** 模型要求 Runtime 执行一个工具。 */
    data class ToolRequest(
        val call: ToolCall,
        val content: String? = null,
    ) : ModelResponse
}

/**
 * 模型产生的工具调用。
 *
 * [arguments] 保留模型边界上的原始 JSON，解析和校验由后续工具层负责。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
