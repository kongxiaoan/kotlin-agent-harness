package com.attuchengmen.agent.tool

import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition

/** 请求的工具名没有对应注册。 */
class UnknownToolException(
    val toolName: String,
) : ToolException("unknown tool \"$toolName\"")

/** 拥有工具名称到实现的唯一映射，并统一执行模型工具请求。 */
class ToolRegistry(
    tools: Iterable<Tool> = emptyList(),
) {
    private val toolsByName = mutableMapOf<String, Tool>()

    /** 当前注册工具的稳定定义快照。 */
    val definitions: List<ToolDefinition>
        get() = toolsByName.values.map { it.definition }

    init {
        tools.forEach(::register)
    }

    /** 注册工具；重复名称会被拒绝，避免调用目标取决于注册顺序。 */
    fun register(tool: Tool) {
        val name = tool.definition.name
        require(name.isNotBlank()) { "tool name must not be blank" }
        require(tool.definition.description.isNotBlank()) { "tool description must not be blank" }
        require(toolsByName.putIfAbsent(name, tool) == null) {
            "tool \"$name\" is already registered"
        }
    }

    /** 将原始参数交给名称匹配的工具。 */
    fun execute(call: ToolCall): String =
        (toolsByName[call.name] ?: throw UnknownToolException(call.name)).execute(call.arguments)
}
