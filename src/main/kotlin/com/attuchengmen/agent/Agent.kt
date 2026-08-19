package com.attuchengmen.agent

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.session.AssistantMessageAdded
import com.attuchengmen.agent.session.ModelRequestPrepared
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.session.SessionProjector
import com.attuchengmen.agent.session.StepEnded
import com.attuchengmen.agent.session.StepStarted
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import com.attuchengmen.agent.session.TurnStarted
import com.attuchengmen.agent.session.ToolCallRequested
import com.attuchengmen.agent.session.ToolResultAdded
import com.attuchengmen.agent.session.UserMessageAdded
import com.attuchengmen.agent.tool.ToolRegistry
import com.attuchengmen.agent.tool.ToolException
import com.attuchengmen.agent.tool.UnexpectedToolException

/** Agent Loop 的部署级安全限制。 */
data class AgentOptions(
    val maxStepsPerTurn: Int,
) {
    init {
        require(maxStepsPerTurn > 0) { "maxStepsPerTurn must be positive" }
    }
}

/** 一个 Turn 在得到最终答案前耗尽了允许的模型请求数。 */
class StepLimitExceededException(
    val maxSteps: Int,
) : IllegalStateException(
    "turn exceeded configured maximum of $maxSteps ${if (maxSteps == 1) "step" else "steps"}",
)

/**
 * 阅读顺序 6：一次最小 Agent 交互的编排者。
 *
 * 该类不保存第二份消息历史。它先把用户输入记录为事实，再从 Session
 * 投影模型上下文，调用可替换的模型，并记录回复与 Turn 终态。
 *
 * 当前支持同步工具驱动的多 Step Turn；取消和异步执行尚未加入。
 */
class Agent(
    private val session: Session,
    private val model: LanguageModel,
    private val tools: ToolRegistry,
    private val options: AgentOptions,
) {
    /**
     * 提交用户内容并返回模型回复。
     *
     * 每次调用记录配对的 Turn 开始和终态。模型失败时异常继续传播，
     * 已记录的用户输入和失败终态保留，且不会追加不存在的 Assistant 回复。
     */
    fun submit(content: String): AssistantMessage {
        val turn = nextTurnNumber()
        session.append(TurnStarted(turn))
        try {
            var step = 1
            var nextUserContent: String? = content
            while (true) {
                when (val result = runStep(turn, step, nextUserContent)) {
                    is StepResult.Answer -> {
                        session.append(TurnEnded(turn, TurnOutcome.Completed))
                        return result.message
                    }

                    StepResult.Continue -> {
                        if (step >= options.maxStepsPerTurn) {
                            throw StepLimitExceededException(options.maxStepsPerTurn)
                        }
                        step += 1
                        nextUserContent = null
                    }
                }
            }
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "unknown model failure"
            session.append(TurnEnded(turn, TurnOutcome.Failed(message)))
            throw error
        }
    }

    /** 一个已开始的 Step 无论模型成功或失败都记录结束边界。 */
    private fun runStep(turn: Int, step: Int, content: String?): StepResult {
        session.append(StepStarted(turn, step))
        try {
            if (content != null) session.append(UserMessageAdded(content))
            val request = ModelRequest(
                messages = SessionProjector.toMessages(session.events),
                tools = tools.definitions,
            )
            session.append(ModelRequestPrepared(turn, step, request.tools))
            return when (val response = model.generate(request)) {
                is ModelResponse.Answer -> {
                    session.append(AssistantMessageAdded(response.message.content))
                    StepResult.Answer(response.message)
                }

                is ModelResponse.ToolRequest -> {
                    session.append(ToolCallRequested(turn, step, response.call, response.content))
                    try {
                        val result = tools.execute(response.call)
                        session.append(ToolResultAdded(turn, step, response.call.id, result, isError = false))
                    } catch (error: ToolException) {
                        session.append(
                            ToolResultAdded(
                                turn,
                                step,
                                response.call.id,
                                "Error: ${error.message}",
                                isError = true,
                            ),
                        )
                    } catch (error: Exception) {
                        session.append(
                            ToolResultAdded(
                                turn,
                                step,
                                response.call.id,
                                "Error: internal tool failure",
                                isError = true,
                            ),
                        )
                        throw UnexpectedToolException(error)
                    }
                    StepResult.Continue
                }
            }
        } finally {
            session.append(StepEnded(turn, step))
        }
    }

    /** 从事实日志推导递增编号，避免 Agent 持有另一份轮次状态。 */
    private fun nextTurnNumber(): Int =
        (session.events.filterIsInstance<TurnStarted>().maxOfOrNull { it.turn } ?: 0) + 1
}

/** 一个 Step 对所属 Turn 的控制结果。 */
private sealed interface StepResult {
    data class Answer(val message: AssistantMessage) : StepResult

    data object Continue : StepResult
}
