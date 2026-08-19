package com.attuchengmen.agent.session

/**
 * Session 事件的追加式存储边界。
 *
 * 实现必须保持追加顺序，并返回不会随后续追加变化的事件快照。
 */
interface SessionLog {
    val events: List<SessionEvent>

    fun append(event: SessionEvent)
}

/** 不跨进程保存事件的默认 SessionLog。 */
class InMemorySessionLog : SessionLog {
    private val eventLog = mutableListOf<SessionEvent>()

    override val events: List<SessionEvent>
        get() = synchronized(eventLog) { eventLog.toList() }

    override fun append(event: SessionEvent) {
        synchronized(eventLog) {
            eventLog.add(event)
        }
    }
}
