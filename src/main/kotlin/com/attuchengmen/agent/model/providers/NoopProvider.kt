package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse

/** 不访问外部服务、始终返回固定答案的本地模型实现。 */
class NoopProvider : LanguageModel {
    override fun generate(request: ModelRequest): ModelResponse =
        ModelResponse.Answer(AssistantMessage("上下文消息${request.messages} , 模型说 ${System.currentTimeMillis()}"))
}
