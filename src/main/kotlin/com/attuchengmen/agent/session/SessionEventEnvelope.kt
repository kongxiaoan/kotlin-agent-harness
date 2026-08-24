package com.attuchengmen.agent.session

import java.time.Instant
import java.util.UUID

/** 持久化 Session 的不可互换标识。 */
@JvmInline
value class SessionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "session id must not be blank" }
    }

    companion object {
        /** 生成服务端使用的随机 Session 标识。 */
        fun generate(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}

/**
 * 一个可持久化、可按游标读取的 Session 运行事实。
 *
 * [sequence] 在所属 Session 内从 1 连续递增，是排序和断点续传的依据；
 * [occurredAt] 用于观测和审计，不替代序号承担排序职责。
 */
data class SessionEventEnvelope(
    val sessionId: SessionId,
    val sequence: Long,
    val occurredAt: Instant,
    val event: SessionEvent,
) {
    init {
        require(sequence > 0) { "session event sequence must be positive" }
    }
}

/** Session 内一段包含首尾事件的连续序号范围。 */
data class SessionEventRange(
    val fromSequence: Long,
    val toSequence: Long,
) {
    init {
        require(fromSequence > 0) { "event range start must be positive" }
        require(toSequence >= fromSequence) { "event range end must not precede its start" }
    }
}
