package com.attuchengmen.agent.tool

import com.attuchengmen.agent.model.ToolDefinition

/** Agent Runtime 可以按名称调用的一项同步能力。 */
interface Tool {
    val definition: ToolDefinition

    /** 执行模型提供的原始 JSON 参数并返回模型可见文本。 */
    fun execute(arguments: String): String
}

/** 可以安全返回给模型并允许其修正请求的工具错误。 */
sealed class ToolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 模型提供的工具参数不符合该工具要求。 */
class ToolArgumentsException(
    toolName: String,
    detail: String,
    cause: Throwable? = null,
) : ToolException("invalid arguments for tool \"$toolName\": $detail", cause)

/** 工具参数有效，但执行时无法产生结果。 */
class ToolExecutionException(
    toolName: String,
    detail: String,
    cause: Throwable? = null,
) : ToolException("tool \"$toolName\" failed: $detail", cause)

/** 未分类工具异常的稳定外部错误；原始异常仅作为 cause 保留。 */
class UnexpectedToolException(
    cause: Throwable,
) : IllegalStateException("internal tool failure", cause)
