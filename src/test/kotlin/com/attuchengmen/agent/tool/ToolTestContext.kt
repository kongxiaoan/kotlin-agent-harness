package com.attuchengmen.agent.tool

import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.AgentIdentity
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId

internal val TEST_TOOL_CONTEXT = ToolExecutionContext(
    identity = AgentIdentity(TenantId("tenant-1"), UserId("user-1"), AgentId("agent-1")),
    sessionId = SessionId("session-1"),
    turn = 1,
    step = 1,
    sourceEventRange = SessionEventRange(1, 3),
)
