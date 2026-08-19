package com.attuchengmen.agent.model

/** Provider 流式返回的封闭事件集合。 */
sealed interface ModelChunk {
    /** 最终响应产生前的一段可见文本。 */
    data class TextDelta(
        val text: String,
    ) : ModelChunk

    /** 一个工具调用的名称或原始 JSON 参数增量。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String,
        val name: String? = null,
        val argumentsDelta: String,
    ) : ModelChunk {
        init {
            require(index >= 0) { "tool call index must not be negative" }
        }
    }

    /** Provider 对当前 attempt 报告的完整用量；后到值覆盖先到值。 */
    data class Usage(
        val usage: TokenUsage,
    ) : ModelChunk

    /** 流的唯一终态，携带组装后的完整响应及停止原因。 */
    data class Finished(
        val response: ModelResponse,
        val reason: ModelFinishReason = defaultFinishReason(response),
    ) : ModelChunk
}

/** 模型流违反 chunk 顺序或最终响应一致性。 */
class ModelStreamProtocolException(
    detail: String,
) : ModelRequestException("invalid model stream: $detail", retryable = false)

/** 将一个 Provider attempt 的原始 chunk 验证并折叠为完整响应。 */
class ModelChunkAssembler {
    private val text = StringBuilder()
    private var toolCall: PartialToolCall? = null
    private var response: ModelResponse? = null
    var usage: TokenUsage? = null
        private set
    var finishReason: ModelFinishReason? = null
        private set

    /** 接收下一个 chunk；终态之后不允许继续产生内容。 */
    fun push(chunk: ModelChunk) {
        if (response != null) throw ModelStreamProtocolException("chunk received after finish")
        when (chunk) {
            is ModelChunk.TextDelta -> text.append(chunk.text)
            is ModelChunk.ToolCallDelta -> pushToolCall(chunk)
            is ModelChunk.Usage -> usage = chunk.usage
            is ModelChunk.Finished -> {
                validateText(chunk.response)
                validateToolCall(chunk.response)
                validateFinishReason(chunk.response, chunk.reason)
                response = chunk.response
                finishReason = chunk.reason
            }
        }
    }

    /** 返回完整响应；无终态的流视为可重试的中断请求。 */
    fun finish(): ModelResponse = response
        ?: throw ModelRequestException("model stream ended without finish", retryable = true)

    private fun validateText(value: ModelResponse) {
        if (text.isEmpty()) return
        val completedText = when (value) {
            is ModelResponse.Answer -> value.message.content
            is ModelResponse.ToolRequest -> value.content.orEmpty()
        }
        if (text.toString() != completedText) {
            throw ModelStreamProtocolException("text deltas do not match finished response")
        }
    }

    private fun pushToolCall(chunk: ModelChunk.ToolCallDelta) {
        val partial = toolCall ?: PartialToolCall(chunk.index).also { toolCall = it }
        if (partial.index != chunk.index) {
            throw ModelStreamProtocolException("multiple tool calls are not supported")
        }
        if (chunk.id.isNotEmpty()) partial.id = mergeField("id", partial.id, chunk.id)
        if (chunk.name != null) partial.name = mergeField("name", partial.name, chunk.name)
        partial.arguments.append(chunk.argumentsDelta)
    }

    private fun validateToolCall(value: ModelResponse) {
        val partial = toolCall ?: return
        val completed = value as? ModelResponse.ToolRequest
            ?: throw ModelStreamProtocolException("tool deltas finished as a non-tool response")
        if (
            partial.id != completed.call.id ||
            partial.name != completed.call.name ||
            partial.arguments.toString() != completed.call.arguments
        ) {
            throw ModelStreamProtocolException("tool deltas do not match finished response")
        }
    }

    private fun validateFinishReason(value: ModelResponse, reason: ModelFinishReason) {
        val valid = when (value) {
            is ModelResponse.Answer -> reason != ModelFinishReason.TOOL_CALLS
            is ModelResponse.ToolRequest -> reason == ModelFinishReason.TOOL_CALLS
        }
        if (!valid) throw ModelStreamProtocolException("finish reason does not match response type")
    }

    private fun mergeField(field: String, previous: String?, next: String): String {
        if (previous != null && previous != next) {
            throw ModelStreamProtocolException("tool call $field changed during stream")
        }
        return next
    }

    private data class PartialToolCall(
        val index: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}

private fun defaultFinishReason(response: ModelResponse): ModelFinishReason = when (response) {
    is ModelResponse.Answer -> ModelFinishReason.STOP
    is ModelResponse.ToolRequest -> ModelFinishReason.TOOL_CALLS
}
