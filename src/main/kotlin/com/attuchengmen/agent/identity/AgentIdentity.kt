package com.attuchengmen.agent.identity

import com.attuchengmen.agent.session.SessionId

/** 不同租户之间不可共享的身份。 */
@JvmInline
value class TenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "tenant id must not be blank" }
    }
}

/** 租户内使用 Agent 的用户身份。 */
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "user id must not be blank" }
    }
}

/** 同一用户可以使用的一个 Agent 身份。 */
@JvmInline
value class AgentId(val value: String) {
    init {
        require(value.isNotBlank()) { "agent id must not be blank" }
    }
}

/** Runtime 从可信入口解析并传给能力层的调用身份。 */
data class AgentIdentity(
    val tenantId: TenantId,
    val userId: UserId,
    val agentId: AgentId,
) {
    companion object {
        /** 未配置身份时按 Session 隔离，避免意外形成跨 Session 共享状态。 */
        fun isolated(sessionId: SessionId): AgentIdentity = AgentIdentity(
            tenantId = TenantId("isolated-session"),
            userId = UserId(sessionId.value),
            agentId = AgentId("default-agent"),
        )
    }
}
