package com.attuchengmen.agent.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阅读顺序 8：证明 Session 拥有事件顺序和可变状态。
 *
 * “稳定快照”测试防止内部可变集合泄露给调用者。
 */
class SessionTest {
    @Test
    fun `new session has no events`() {
        val session = Session()

        assertTrue(session.events.isEmpty())
    }

    @Test
    fun `append preserves event order`() {
        val session = Session()
        val first = TurnStarted(turn = 1)
        val second = UserMessageAdded("hello")

        session.append(first)
        session.append(second)

        assertEquals(listOf(first, second), session.events)
    }

    @Test
    fun `events returns a stable snapshot`() {
        val session = Session()
        session.append(TurnStarted(turn = 1))
        val snapshot = session.events

        session.append(UserMessageAdded("hello"))

        assertEquals(listOf(TurnStarted(turn = 1)), snapshot)
    }
}
