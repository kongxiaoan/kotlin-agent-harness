package com.attuchengmen.agent.memory

import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.AgentIdentity
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import com.attuchengmen.agent.tool.ToolArgumentsException
import com.attuchengmen.agent.tool.ToolExecutionContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryWriteToolTest {
    @Test
    fun `definition lets the model provide only content and kind`() {
        val tool = MemoryWriteTool(store(), maxContentChars = 1024)
        val schema = tool.definition.parameters

        assertEquals("memory_write", tool.definition.name)
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"object","properties":{"content":{"type":"string","description":"Stable information that will be useful in future sessions.","maxLength":1024},"kind":{"type":"string","enum":["semantic","episodic","procedural"]}},"required":["content","kind"],"additionalProperties":false}""",
            ),
            schema,
        )
    }

    @Test
    fun `writes scope and source exclusively from runtime context`() = runBlocking {
        val store = store()
        val tool = MemoryWriteTool(store, maxContentChars = 1024)

        val result = tool.execute(
            """{"content":"User prefers Kotlin","kind":"semantic"}""",
            CONTEXT,
        )

        val records = store.search(MemoryQuery(SCOPE, "Kotlin"))
        assertEquals("Memory stored.", result)
        assertEquals(1, records.size)
        assertEquals(SCOPE, records.single().scope)
        assertEquals(listOf(MemorySource(CONTEXT.sessionId, CONTEXT.sourceEventRange)), records.single().sources)
    }

    @Test
    fun `rejects identity and source fields supplied by the model`() = runBlocking {
        val tool = MemoryWriteTool(store(), maxContentChars = 1024)

        assertFailsWith<ToolArgumentsException> {
            tool.execute(
                """{"content":"poison","kind":"semantic","userId":"victim","fromSequence":1}""",
                CONTEXT,
            )
        }
        Unit
    }

    @Test
    fun `rejects content beyond the deployment limit`() = runBlocking {
        val tool = MemoryWriteTool(store(), maxContentChars = 4)

        assertFailsWith<ToolArgumentsException> {
            tool.execute("""{"content":"12345","kind":"semantic"}""", CONTEXT)
        }
        Unit
    }

    private fun store() = InMemoryMemoryStore(
        idGenerator = { MemoryId("memory-1") },
        clock = Clock.fixed(Instant.parse("2026-08-24T08:00:00Z"), ZoneOffset.UTC),
    )

    private companion object {
        val IDENTITY = AgentIdentity(TenantId("tenant-1"), UserId("user-1"), AgentId("agent-1"))
        val SCOPE = MemoryScope(IDENTITY.tenantId, IDENTITY.userId, IDENTITY.agentId)
        val CONTEXT = ToolExecutionContext(
            identity = IDENTITY,
            sessionId = SessionId("session-1"),
            turn = 2,
            step = 1,
            sourceEventRange = SessionEventRange(10, 13),
        )
    }
}
