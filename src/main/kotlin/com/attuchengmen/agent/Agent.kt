package com.attuchengmen.agent

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.context.ContextManager
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelChunkAssembler
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelRequestException
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.session.AssistantMessageAdded
import com.attuchengmen.agent.session.ContextPrepared
import com.attuchengmen.agent.session.ModelChunkReceived
import com.attuchengmen.agent.session.ModelRequestPrepared
import com.attuchengmen.agent.session.ModelRetryScheduled
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Agent Loop 的部署级安全限制。 */
data class AgentOptions(
    val maxStepsPerTurn: Int,
    val turnTimeout: Duration,
) {
    init {
        require(maxStepsPerTurn > 0) { "maxStepsPerTurn must be positive" }
        require(!turnTimeout.isZero && !turnTimeout.isNegative && turnTimeout.toMillis() > 0) {
            "turnTimeout must be at least one millisecond"
        }
    }
}

/** 一个 Turn 在得到最终答案前耗尽了允许的模型请求数。 */
class StepLimitExceededException(
    val maxSteps: Int,
) : IllegalStateException(
    "turn exceeded configured maximum of $maxSteps ${if (maxSteps == 1) "step" else "steps"}",
)

/** 一个 Turn 未在部署配置允许的时间内完成。 */
class TurnTimeoutExceededException(
    val timeout: Duration,
) : IllegalStateException("turn exceeded configured timeout of ${timeout.toMillis()} ms")

/**
 * 阅读顺序 6：一次最小 Agent 交互的编排者。
 *
 * 该类不保存第二份消息历史。它先把用户输入记录为事实，再从 Session
 * 投影模型上下文，调用可替换的模型，并记录回复与 Turn 终态。
 *
 * 模型和工具调用可挂起；调用方取消时，当前 Step 与 Turn 仍会记录终态。
 */
class Agent(
    private val session: Session,
    private val model: LanguageModel,
    private val tools: ToolRegistry,
    private val options: AgentOptions,
    private val clock: Clock = Clock.systemUTC(),
    private val contextManager: ContextManager? = null,
) {
    private val turnMutex = Mutex()

    /**
     * 提交用户内容并返回模型回复。
     *
     * 每次调用记录配对的 Turn 开始和终态。模型失败时异常继续传播，
     * 已记录的用户输入和失败终态保留，且不会追加不存在的 Assistant 回复。
     */
    suspend fun submit(content: String): AssistantMessage = turnMutex.withLock {
        runTurn(content)
    }

    /** 在已取得单 Turn 执行权后完成一次提交。 */
    private suspend fun runTurn(content: String): AssistantMessage {
        val turn = nextTurnNumber()
        session.append(TurnStarted(turn))
        try {
            val result = withTimeoutOrNull(options.turnTimeout.toMillis().milliseconds) {
                runSteps(turn, content)
            } ?: throw TurnTimeoutExceededException(options.turnTimeout)
            val outcome = if (result.reason == ModelFinishReason.MAX_TOKENS) {
                TurnOutcome.MaxTokens
            } else {
                TurnOutcome.Completed
            }
            session.append(TurnEnded(turn, outcome))
            return result.message
        } catch (error: TurnTimeoutExceededException) {
            session.append(TurnEnded(turn, TurnOutcome.TimedOut(error.timeout)))
            throw error
        } catch (error: CancellationException) {
            session.append(TurnEnded(turn, TurnOutcome.Cancelled))
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "unknown model failure"
            session.append(TurnEnded(turn, TurnOutcome.Failed(message)))
            throw error
        }
    }

    /** 持续执行 Step，直到得到答案或触发 Step 上限。 */
    private suspend fun runSteps(turn: Int, content: String): StepResult.Answer {
        var step = 1
        var nextUserContent: String? = content
        while (true) {
            when (val result = runStep(turn, step, nextUserContent)) {
                is StepResult.Answer -> return result
                StepResult.Continue -> {
                    if (step >= options.maxStepsPerTurn) {
                        throw StepLimitExceededException(options.maxStepsPerTurn)
                    }
                    step += 1
                    nextUserContent = null
                }
            }
        }
    }

    /** 一个已开始的 Step 无论模型成功或失败都记录结束边界。 */
    private suspend fun runStep(turn: Int, step: Int, content: String?): StepResult {
        session.append(StepStarted(turn, step))
        try {
            if (content != null) session.append(UserMessageAdded(content))
            val modelResult = generateWithRetry(turn, step)
            return when (val response = modelResult.response) {
                is ModelResponse.Answer -> {
                    if (response.message.content.isNotEmpty()) {
                        session.append(AssistantMessageAdded(response.message.content))
                    }
                    StepResult.Answer(response.message, modelResult.reason)
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
                    } catch (error: CancellationException) {
                        throw error
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

    /** 只重试 Provider 明确标记为瞬时失败的模型请求。 */
    private suspend fun generateWithRetry(turn: Int, step: Int): ModelCallResult {
        var retry = 0
        while (true) {
            val attempt = retry + 1
            val contextPlan = contextManager?.build(session.envelopes, turn, tools.definitions)
            val request = contextPlan?.request ?: ModelRequest(
                messages = SessionProjector.toMessages(session.events),
                tools = tools.definitions,
            )
            if (contextPlan != null) {
                session.append(
                    ContextPrepared(
                        turn = turn,
                        step = step,
                        attempt = attempt,
                        selectedEventRanges = contextPlan.selectedEventRanges,
                        estimatedInputTokens = contextPlan.estimatedInputTokens,
                        inputTokenBudget = contextPlan.inputTokenBudget,
                        tokenEstimatorId = contextPlan.tokenEstimatorId,
                    ),
                )
            }
            session.append(
                ModelRequestPrepared(
                    turn = turn,
                    step = step,
                    tools = request.tools,
                    attempt = attempt,
                    maxOutputTokens = request.maxOutputTokens,
                    profile = model.profile,
                ),
            )
            try {
                val assembler = ModelChunkAssembler()
                model.stream(request).collect { chunk ->
                    session.append(
                        ModelChunkReceived(
                            turn = turn,
                            step = step,
                            attempt = attempt,
                            chunk = chunk,
                            observedAt = if (chunk is ModelChunk.Usage) {
                                clock.instant()
                            } else {
                                null
                            },
                        ),
                    )
                    assembler.push(chunk)
                }
                val response = assembler.finish()
                val reason = checkNotNull(assembler.finishReason) { "finished model stream has no finish reason" }
                return ModelCallResult(response, reason)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ModelRequestException) {
                val policy = model.retryPolicy
                if (!error.retryable || policy == null || retry >= policy.maxRetries) throw error
                retry += 1
                val delayMillis = policy.delayMillis(retry)
                session.append(ModelRetryScheduled(turn, step, retry, delayMillis, error.message.orEmpty()))
                delay(delayMillis.milliseconds)
            }
        }
    }

    /** 从事实日志推导递增编号，避免 Agent 持有另一份轮次状态。 */
    private fun nextTurnNumber(): Int =
        (session.events.filterIsInstance<TurnStarted>().maxOfOrNull { it.turn } ?: 0) + 1
}

/** 一个 Step 对所属 Turn 的控制结果。 */
private sealed interface StepResult {
    data class Answer(
        val message: AssistantMessage,
        val reason: ModelFinishReason,
    ) : StepResult

    data object Continue : StepResult
}

private data class ModelCallResult(
    val response: ModelResponse,
    val reason: ModelFinishReason,
)
