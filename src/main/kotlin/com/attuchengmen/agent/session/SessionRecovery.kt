package com.attuchengmen.agent.session

/** 为进程崩溃留下的开放 Turn 生成确定性的追加事件。 */
object SessionRecovery {
    /** 已记录工具调用但没有持久化结果时返回给模型的保守说明。 */
    const val TOOL_OUTCOME_UNKNOWN_MESSAGE =
        "Error: tool execution was interrupted after its call was recorded, but no result was durably recorded. " +
            "Its outcome is unknown. Retry only if the tool is read-only or idempotent; otherwise verify external state first."

    /**
     * 返回关闭日志尾部开放 Turn 所需的事件。
     * 未配对工具结果必须先于 Step 和 Turn 终态，保证恢复后的模型消息合法。
     */
    fun interruptedTurnClosers(events: List<SessionEvent>): List<SessionEvent> {
        var openTurn: Int? = null
        var openStep: Pair<Int, Int>? = null
        val pendingCalls = linkedMapOf<String, ToolCallRequested>()

        for (event in events) {
            when (event) {
                is TurnStarted -> {
                    openTurn = event.turn
                    openStep = null
                    pendingCalls.clear()
                }
                is TurnEnded -> {
                    openTurn = null
                    openStep = null
                    pendingCalls.clear()
                }
                is StepStarted -> openStep = event.turn to event.step
                is StepEnded -> {
                    openStep = null
                    pendingCalls.clear()
                }
                is ToolCallRequested -> pendingCalls[event.call.id] = event
                is ToolResultAdded -> pendingCalls.remove(event.callId)
                is UserMessageAdded,
                is AssistantMessageAdded,
                is ModelRequestPrepared,
                is ModelRetryScheduled,
                is ModelChunkReceived,
                -> Unit
            }
        }

        val turn = openTurn ?: return emptyList()
        return buildList {
            for ((callId, request) in pendingCalls) {
                add(
                    ToolResultAdded(
                        turn = request.turn,
                        step = request.step,
                        callId = callId,
                        content = TOOL_OUTCOME_UNKNOWN_MESSAGE,
                        isError = true,
                    ),
                )
            }
            openStep?.let { (stepTurn, step) -> add(StepEnded(stepTurn, step)) }
            add(TurnEnded(turn, TurnOutcome.Interrupted))
        }
    }
}
