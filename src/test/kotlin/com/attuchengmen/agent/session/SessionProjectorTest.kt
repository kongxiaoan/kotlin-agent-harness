package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 阅读顺序 7：证明事件到模型上下文的投影规则。
 *
 * 这里关注空输入、顺序保持、工具交换投影和生命周期事件过滤，不测试
 * 实现采用 `map` 还是循环，因此重构内部算法不会破坏行为测试。
 */
class SessionProjectorTest {
    @Test
    fun `empty event log produces empty message history`() {
        assertEquals(emptyList(), SessionProjector.toMessages(emptyList()))
    }

    @Test
    fun `message events produce model messages in event order`() {
        val events = listOf(
            UserMessageAdded("hello"),
            AssistantMessageAdded("hi"),
            UserMessageAdded("how are you"),
        )

        assertEquals(
            listOf(
                UserMessage("hello"),
                AssistantMessage("hi"),
                UserMessage("how are you"),
            ),
            SessionProjector.toMessages(events),
        )
    }

    @Test
    fun `tool exchange enters model history while lifecycle events do not`() {
        val call = ToolCall("call-1", "read_file", "{\"path\":\"README.md\"}")
        val events = listOf(
            TurnStarted(turn = 1),
            StepStarted(turn = 1, step = 1),
            UserMessageAdded("hello"),
            ToolCallRequested(
                turn = 1,
                step = 1,
                call = call,
            ),
            ToolResultAdded(1, 1, "call-1", "project content", isError = false),
            StepEnded(turn = 1, step = 1),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
        )

        assertEquals(
            listOf(
                UserMessage("hello"),
                ToolCallMessage(call),
                ToolResultMessage("call-1", "project content", isError = false),
            ),
            SessionProjector.toMessages(events),
        )
    }

    @Test
    fun `request reconstruction uses messages before its recorded boundary`() {
        val tool = ToolDefinition(
            name = "read_file",
            description = "Read a file.",
            parameters = buildJsonObject { put("type", "object") },
        )
        val events = listOf(
            TurnStarted(turn = 1),
            StepStarted(turn = 1, step = 1),
            UserMessageAdded("hello"),
            ModelRequestPrepared(turn = 1, step = 1, tools = listOf(tool)),
            AssistantMessageAdded("future response"),
            StepEnded(turn = 1, step = 1),
        )

        assertEquals(
            ModelRequest(messages = listOf(UserMessage("hello")), tools = listOf(tool)),
            SessionProjector.toRequest(events, turn = 1, step = 1),
        )
    }
}
