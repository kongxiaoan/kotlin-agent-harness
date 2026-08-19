package com.attuchengmen.agent.model

import com.attuchengmen.agent.message.Message
import kotlinx.serialization.json.JsonObject

/** 一次模型调用所需的完整输入。 */
data class ModelRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
)

/** 模型可见的工具名称、用途和参数 JSON Schema。 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
