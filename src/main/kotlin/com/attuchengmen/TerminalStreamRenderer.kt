package com.attuchengmen

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.session.ModelChunkReceived
import com.attuchengmen.agent.session.ModelRetryScheduled
import com.attuchengmen.agent.session.SessionEvent
import com.attuchengmen.agent.session.StepStarted
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import java.io.PrintStream

/** 实时输出文本 chunk，并明确分隔失败 attempt 与后续重试。 */
internal class TerminalStreamRenderer(
    private val output: PrintStream,
) {
    private var latestStep: StepCoordinate? = null
    private var lastTextAttempt: AttemptCoordinate? = null
    private val lastAttemptText = StringBuilder()
    private var lineOpen = false
    private var maxTokensTurn: Int? = null

    /** 接收 Session 实时事件；重试事件将已显示的失败片段标记为废弃。 */
    @Synchronized
    fun onEvent(event: SessionEvent) {
        if (event is StepStarted) latestStep = StepCoordinate(event.turn, event.step)
        if (event is TurnEnded && event.outcome == TurnOutcome.MaxTokens) {
            closeLine()
            maxTokensTurn = event.turn
            return
        }
        if (event is ModelRetryScheduled) {
            markRetry(event)
            return
        }
        val received = event as? ModelChunkReceived ?: return
        val delta = received.chunk as? ModelChunk.TextDelta ?: return
        if (delta.text.isEmpty()) return

        val step = StepCoordinate(received.turn, received.step)
        val attempt = AttemptCoordinate(received.turn, received.step, received.attempt)
        latestStep = step
        if (lastTextAttempt != attempt) {
            closeLine()
            lastAttemptText.clear()
            lastTextAttempt = attempt
        }
        output.print(delta.text)
        output.flush()
        lastAttemptText.append(delta.text)
        lineOpen = true
    }

    /** 完成一次提交；未通过 chunk 展示的最终答案在此补充输出。 */
    @Synchronized
    fun finish(message: AssistantMessage) {
        val truncated = maxTokensTurn == latestStep?.turn
        val finalWasStreamed =
            lastTextAttempt?.stepCoordinate == latestStep && lastAttemptText.toString() == message.content
        if (finalWasStreamed) {
            closeLine()
        } else {
            closeLine()
            if (!truncated || message.content.isNotEmpty()) output.println(message.content)
        }
        if (truncated) output.println("[model output truncated: max tokens]")
    }

    /** 异常结束时关闭已经开始的终端行。 */
    @Synchronized
    fun finishFailure() {
        closeLine()
    }

    private fun markRetry(event: ModelRetryScheduled) {
        val failed = lastTextAttempt
        if (failed?.stepCoordinate != StepCoordinate(event.turn, event.step) || failed.attempt != event.retry) return
        closeLine()
        output.println("[model retry ${event.retry}]")
        lastTextAttempt = null
        lastAttemptText.clear()
    }

    private fun closeLine() {
        if (!lineOpen) return
        output.println()
        lineOpen = false
    }
}

private data class StepCoordinate(
    val turn: Int,
    val step: Int,
)

private data class AttemptCoordinate(
    val turn: Int,
    val step: Int,
    val attempt: Int,
) {
    val stepCoordinate: StepCoordinate = StepCoordinate(turn, step)
}
