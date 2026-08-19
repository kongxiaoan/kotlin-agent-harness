package com.attuchengmen.agent.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阅读顺序 8：证明 Session 拥有事件顺序和可变状态。
 *
 * “稳定快照”测试防止内部可变集合泄露给调用者。
 */
class SessionTest {
    @Test
    fun `subscribers observe persisted events in order and can unsubscribe`() {
        val session = Session()
        val observed = mutableListOf<SessionEvent>()
        val subscription = session.subscribe(observed::add)
        val first = TurnStarted(turn = 1)

        session.append(first)
        subscription.close()
        session.append(TurnEnded(turn = 1, outcome = TurnOutcome.Completed))

        assertEquals(listOf<SessionEvent>(first), observed)
    }

    @Test
    fun `observer failure does not fail an already persisted append`() {
        val session = Session()
        val observed = mutableListOf<SessionEvent>()
        session.subscribe { error("renderer failed") }
        session.subscribe(observed::add)

        session.append(TurnStarted(turn = 1))

        assertEquals(session.events, observed)
    }

    @Test
    fun `failed persistence is not published`() {
        val session = Session(object : SessionLog {
            override val events: List<SessionEvent> = emptyList()
            override fun append(event: SessionEvent) = error("disk unavailable")
        })
        var observed = false
        session.subscribe { observed = true }

        assertFailsWith<IllegalStateException> { session.append(TurnStarted(turn = 1)) }

        assertFalse(observed)
    }

    @Test
    fun `observer reentrant append preserves order for every observer`() {
        val session = Session()
        val observed = mutableListOf<SessionEvent>()
        session.subscribe { event ->
            if (event is TurnStarted) session.append(UserMessageAdded("nested"))
        }
        session.subscribe(observed::add)

        session.append(TurnStarted(turn = 1))

        assertEquals(
            listOf<SessionEvent>(TurnStarted(turn = 1), UserMessageAdded("nested")),
            observed,
        )
    }

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
