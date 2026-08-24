package com.attuchengmen.agent.session

/** 请求创建的 Session 已经存在。 */
class StoredSessionAlreadyExistsException(
    val sessionId: SessionId,
) : IllegalStateException("session ${sessionId.value} already exists")

/** 请求访问的 Session 不存在。 */
class StoredSessionNotFoundException(
    val sessionId: SessionId,
) : NoSuchElementException("session ${sessionId.value} does not exist")

/**
 * 多 Session 事件存储边界。
 *
 * [append] 必须原子地校验当前序号与 [expectedSequence] 并追加事件信封。
 */
interface SessionEventStore {
    fun createSession(sessionId: SessionId)

    fun read(
        sessionId: SessionId,
        afterSequence: Long = 0,
    ): List<SessionEventEnvelope>

    fun append(
        sessionId: SessionId,
        expectedSequence: Long,
        envelope: SessionEventEnvelope,
    )
}

/** 进程内的多 Session 事件存储，提供数据库实现的参考语义。 */
class InMemorySessionEventStore : SessionEventStore {
    private val lock = Any()
    private val sessions = mutableMapOf<SessionId, MutableList<SessionEventEnvelope>>()

    override fun createSession(sessionId: SessionId) {
        synchronized(lock) {
            if (sessions.containsKey(sessionId)) {
                throw StoredSessionAlreadyExistsException(sessionId)
            }
            sessions[sessionId] = mutableListOf()
        }
    }

    override fun read(
        sessionId: SessionId,
        afterSequence: Long,
    ): List<SessionEventEnvelope> {
        require(afterSequence >= 0) { "after sequence must not be negative" }
        return synchronized(lock) {
            sessionEvents(sessionId).filter { it.sequence > afterSequence }
        }
    }

    override fun append(
        sessionId: SessionId,
        expectedSequence: Long,
        envelope: SessionEventEnvelope,
    ) {
        synchronized(lock) {
            val events = sessionEvents(sessionId)
            val actualSequence = events.lastOrNull()?.sequence ?: 0
            validateExpectedSequence(sessionId, expectedSequence, actualSequence)
            validateNextEnvelope(sessionId, events.lastOrNull()?.sequence, envelope)
            events.add(envelope)
        }
    }

    private fun sessionEvents(sessionId: SessionId): MutableList<SessionEventEnvelope> {
        return sessions[sessionId] ?: throw StoredSessionNotFoundException(sessionId)
    }
}

/** 将中心化 [SessionEventStore] 适配为单 Session 的 [SessionLog]。 */
class EventStoreSessionLog private constructor(
    private val store: SessionEventStore,
    override val sessionId: SessionId,
) : SessionLog {
    override val envelopes: List<SessionEventEnvelope>
        get() = store.read(sessionId)

    override fun append(expectedSequence: Long, envelope: SessionEventEnvelope) {
        store.append(sessionId, expectedSequence, envelope)
    }

    companion object {
        /** 创建新的持久化 Session。 */
        fun create(
            store: SessionEventStore,
            sessionId: SessionId = SessionId.generate(),
        ): EventStoreSessionLog {
            store.createSession(sessionId)
            return EventStoreSessionLog(store, sessionId)
        }

        /** 打开已经存在的持久化 Session。 */
        fun open(
            store: SessionEventStore,
            sessionId: SessionId,
        ): EventStoreSessionLog {
            store.read(sessionId)
            return EventStoreSessionLog(store, sessionId)
        }
    }
}
