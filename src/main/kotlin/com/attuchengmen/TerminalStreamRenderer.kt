package com.attuchengmen

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.session.ModelChunkReceived
import com.attuchengmen.agent.session.SessionEvent
import com.attuchengmen.agent.session.StepStarted
import java.io.PrintStream

/** 将 Session 的文本 chunk 实时输出，并避免完成后重复打印最终答案。 */
internal class TerminalStreamRenderer(
    private val output: PrintStream,
) {
    private var latestStep: StepCoordinate? = null
    private var lastTextStep: StepCoordinate? = null
    private val lastStepText = StringBuilder()
    private var wroteText = false

    /** 接收 Session 实时事件；非文本事件只用于跟踪最终答案所属 Step。 */
    @Synchronized
    fun onEvent(event: SessionEvent) {
        if (event is StepStarted) latestStep = StepCoordinate(event.turn, event.step)
        val received = event as? ModelChunkReceived ?: return
        val delta = received.chunk as? ModelChunk.TextDelta ?: return
        if (delta.text.isEmpty()) return

        val coordinate = StepCoordinate(received.turn, received.step)
        latestStep = coordinate
        if (lastTextStep != coordinate) {
            if (wroteText) output.println()
            lastStepText.clear()
            lastTextStep = coordinate
        }
        output.print(delta.text)
        output.flush()
        lastStepText.append(delta.text)
        wroteText = true
    }

    /** 完成一次提交；未通过 chunk 展示的最终答案在此补充输出。 */
    @Synchronized
    fun finish(message: AssistantMessage) {
        val finalWasStreamed = wroteText && lastTextStep == latestStep && lastStepText.toString() == message.content
        if (finalWasStreamed) {
            output.println()
        } else {
            if (wroteText) output.println()
            output.println(message.content)
        }
    }

    /** 异常结束时关闭已经开始的终端行。 */
    @Synchronized
    fun finishFailure() {
        if (wroteText) output.println()
    }
}

private data class StepCoordinate(
    val turn: Int,
    val step: Int,
)
