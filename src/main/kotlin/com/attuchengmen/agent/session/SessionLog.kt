package com.attuchengmen.agent.session

/**
 * Session 事件信封的追加式存储边界。
 *
 * 实现必须校验 Session 归属和连续序号，并返回不会随后续追加变化的信封快照。
 */
interface SessionLog {
    val sessionId: SessionId
    val envelopes: List<SessionEventEnvelope>

    fun append(expectedSequence: Long, envelope: SessionEventEnvelope)
}

/** 写入方持有的 Session 序号与存储中的最新序号不一致。 */
class SessionSequenceConflictException(
    val sessionId: SessionId,
    val expectedSequence: Long,
    val actualSequence: Long,
) : IllegalStateException(
    "session ${sessionId.value} sequence conflict: expected $expectedSequence, actual $actualSequence",
)

/** 不跨进程保存事件的默认 SessionLog。 */
class InMemorySessionLog(
    override val sessionId: SessionId = SessionId.generate(),
) : SessionLog {
    private val eventLog = mutableListOf<SessionEventEnvelope>()

    override val envelopes: List<SessionEventEnvelope>
        get() = synchronized(eventLog) { eventLog.toList() }

    override fun append(expectedSequence: Long, envelope: SessionEventEnvelope) {
        synchronized(eventLog) {
            validateExpectedSequence(sessionId, expectedSequence, eventLog.lastOrNull()?.sequence ?: 0)
            validateNextEnvelope(sessionId, eventLog.lastOrNull()?.sequence, envelope)
            eventLog.add(envelope)
        }
    }
}

/** 检查写入方读取的最新序号仍与存储一致。 */
internal fun validateExpectedSequence(
    sessionId: SessionId,
    expectedSequence: Long,
    actualSequence: Long,
) {
    require(expectedSequence >= 0) { "expected sequence must not be negative" }
    if (expectedSequence != actualSequence) {
        throw SessionSequenceConflictException(sessionId, expectedSequence, actualSequence)
    }
}

/** 检查单 Session 追加必须满足的归属和连续序号不变量。 */
internal fun validateNextEnvelope(
    sessionId: SessionId,
    previousSequence: Long?,
    envelope: SessionEventEnvelope,
) {
    require(envelope.sessionId == sessionId) {
        "event belongs to session ${envelope.sessionId.value}, expected ${sessionId.value}"
    }
    val expected = (previousSequence ?: 0) + 1
    require(envelope.sequence == expected) {
        "event sequence ${envelope.sequence} is not the expected sequence $expected"
    }
}
