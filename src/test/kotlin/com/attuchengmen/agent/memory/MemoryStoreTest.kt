package com.attuchengmen.agent.memory

import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemoryStoreTest {
    @Test
    fun `creates grounded memory inside one mandatory scope`() = runBlocking {
        val store = store()

        val memory = store.create(
            NewMemory(SCOPE, MemoryKind.SEMANTIC, "User prefers Kotlin", listOf(SOURCE_1)),
        )

        assertEquals(MemoryId("memory-1"), memory.id)
        assertEquals(SCOPE, memory.scope)
        assertEquals(1L, memory.version)
        assertEquals(NOW, memory.createdAt)
        assertEquals(memory, store.get(SCOPE, memory.id))
    }

    @Test
    fun `same id is invisible outside its complete scope`() = runBlocking {
        val store = store()
        val memory = store.create(
            NewMemory(SCOPE, MemoryKind.SEMANTIC, "private preference", listOf(SOURCE_1)),
        )
        val anotherUser = SCOPE.copy(userId = UserId("user-2"))
        val anotherAgent = SCOPE.copy(agentId = AgentId("agent-2"))

        assertNull(store.get(anotherUser, memory.id))
        assertNull(store.get(anotherAgent, memory.id))
        assertEquals(emptyList(), store.search(MemoryQuery(anotherUser, "preference")))
        assertFailsWith<MemoryNotFoundException> {
            store.replace(
                ReplaceMemory(anotherUser, memory.id, 1, "overwrite", listOf(SOURCE_2)),
            )
        }
        assertFailsWith<MemoryNotFoundException> {
            store.forget(ForgetMemory(anotherAgent, memory.id, 1))
        }
        assertEquals("private preference", store.get(SCOPE, memory.id)?.content)
    }

    @Test
    fun `search returns only active matching memories in deterministic order`() = runBlocking {
        val ids = ArrayDeque(listOf(MemoryId("memory-1"), MemoryId("memory-2"), MemoryId("memory-3")))
        val store = InMemoryMemoryStore({ ids.removeFirst() }, FIXED_CLOCK)
        store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "Kotlin preferred", listOf(SOURCE_1)))
        store.create(NewMemory(SCOPE, MemoryKind.PROCEDURAL, "Use Gradle for Kotlin", listOf(SOURCE_1)))
        store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "Unrelated", listOf(SOURCE_1)))

        val result = store.search(MemoryQuery(SCOPE, "kotlin", limit = 1))

        assertEquals(listOf("Kotlin preferred"), result.map(MemoryRecord::content))
    }

    @Test
    fun `list returns recent active memories only inside the requested scope`() = runBlocking {
        val ids = ArrayDeque(listOf(MemoryId("memory-2"), MemoryId("memory-1"), MemoryId("memory-3")))
        val store = InMemoryMemoryStore({ ids.removeFirst() }, FIXED_CLOCK)
        store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "first", listOf(SOURCE_1)))
        store.create(NewMemory(SCOPE, MemoryKind.PROCEDURAL, "second", listOf(SOURCE_1)))
        store.create(
            NewMemory(SCOPE.copy(userId = UserId("other")), MemoryKind.SEMANTIC, "private", listOf(SOURCE_1)),
        )

        val result = store.list(MemoryListQuery(SCOPE, limit = 1))

        assertEquals(listOf("second"), result.map(MemoryRecord::content))
    }

    @Test
    fun `replace requires the current version and preserves source lineage`() = runBlocking {
        val store = store()
        val created = store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "Uses Java", listOf(SOURCE_1)))

        val replaced = store.replace(
            ReplaceMemory(SCOPE, created.id, expectedVersion = 1, content = "Uses Kotlin", sources = listOf(SOURCE_2)),
        )

        assertEquals(2L, replaced.version)
        assertEquals("Uses Kotlin", replaced.content)
        assertEquals(listOf(SOURCE_1, SOURCE_2), replaced.sources)
        val failure = assertFailsWith<MemoryVersionConflictException> {
            store.replace(
                ReplaceMemory(SCOPE, created.id, expectedVersion = 1, content = "stale", sources = listOf(SOURCE_2)),
            )
        }
        assertEquals(2L, failure.actualVersion)
        assertEquals("Uses Kotlin", store.get(SCOPE, created.id)?.content)
    }

    @Test
    fun `forget removes content and stale writes cannot restore it`() = runBlocking {
        val store = store()
        val created = store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "secret", listOf(SOURCE_1)))

        store.forget(ForgetMemory(SCOPE, created.id, expectedVersion = 1))

        assertNull(store.get(SCOPE, created.id))
        assertEquals(emptyList(), store.search(MemoryQuery(SCOPE, "secret")))
        val failure = assertFailsWith<MemoryVersionConflictException> {
            store.replace(
                ReplaceMemory(SCOPE, created.id, expectedVersion = 1, content = "restore", sources = listOf(SOURCE_2)),
            )
        }
        assertEquals(2L, failure.actualVersion)
    }

    private fun store(): InMemoryMemoryStore =
        InMemoryMemoryStore({ MemoryId("memory-1") }, FIXED_CLOCK)

    private companion object {
        val SCOPE = MemoryScope(TenantId("tenant-1"), UserId("user-1"), AgentId("agent-1"))
        val SOURCE_1 = MemorySource(SessionId("session-1"), SessionEventRange(1, 4))
        val SOURCE_2 = MemorySource(SessionId("session-2"), SessionEventRange(8, 9))
        val NOW: Instant = Instant.parse("2026-08-24T08:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
