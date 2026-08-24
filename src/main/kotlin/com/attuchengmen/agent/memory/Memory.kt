package com.attuchengmen.agent.memory

import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import java.time.Instant
import java.util.UUID

/** 一条长期记忆的不可互换标识。 */
@JvmInline
value class MemoryId(val value: String) {
    init {
        require(value.isNotBlank()) { "memory id must not be blank" }
    }

    companion object {
        fun generate(): MemoryId = MemoryId(UUID.randomUUID().toString())
    }
}

/** 一次检索必须完全匹配的租户、用户和 Agent 作用域。 */
data class MemoryScope(
    val tenantId: TenantId,
    val userId: UserId,
    val agentId: AgentId,
)

/** 记忆表达的知识类型。 */
enum class MemoryKind {
    SEMANTIC,
    EPISODIC,
    PROCEDURAL,
}

/** 记忆内容在 Session 事实中的可审计来源。 */
data class MemorySource(
    val sessionId: SessionId,
    val eventRange: SessionEventRange,
)

/** 当前可检索的一条长期记忆。 */
data class MemoryRecord(
    val id: MemoryId,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val sources: List<MemorySource>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(content.isNotBlank()) { "memory content must not be blank" }
        require(sources.isNotEmpty()) { "memory must have at least one source" }
        require(version > 0) { "memory version must be positive" }
        require(!updatedAt.isBefore(createdAt)) { "memory update time must not precede creation time" }
    }
}

/** 创建一条有明确来源的长期记忆。 */
data class NewMemory(
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val sources: List<MemorySource>,
) {
    init {
        require(content.isNotBlank()) { "memory content must not be blank" }
        require(sources.isNotEmpty()) { "memory must have at least one source" }
    }
}

/** 以乐观并发版本替换记忆内容。 */
data class ReplaceMemory(
    val scope: MemoryScope,
    val id: MemoryId,
    val expectedVersion: Long,
    val content: String,
    val sources: List<MemorySource>,
) {
    init {
        require(expectedVersion > 0) { "expected memory version must be positive" }
        require(content.isNotBlank()) { "memory content must not be blank" }
        require(sources.isNotEmpty()) { "replacement memory must have at least one source" }
    }
}

/** 以乐观并发版本遗忘一条记忆。 */
data class ForgetMemory(
    val scope: MemoryScope,
    val id: MemoryId,
    val expectedVersion: Long,
) {
    init {
        require(expectedVersion > 0) { "expected memory version must be positive" }
    }
}

/** 在单一身份作用域内执行的文本检索。 */
data class MemoryQuery(
    val scope: MemoryScope,
    val text: String,
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val limit: Int = 10,
) {
    init {
        require(text.isNotBlank()) { "memory query must not be blank" }
        require(kinds.isNotEmpty()) { "memory query kinds must not be empty" }
        require(limit > 0) { "memory query limit must be positive" }
    }
}
