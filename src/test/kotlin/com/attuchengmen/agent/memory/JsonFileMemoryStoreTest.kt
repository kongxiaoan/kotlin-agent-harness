package com.attuchengmen.agent.memory

import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JsonFileMemoryStoreTest {
    @Test
    fun `created and replaced memory survives reopening`() = withStorePath { path -> runBlocking {
        val first = store(path, "memory-1")
        val created = first.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "Uses Java", listOf(SOURCE_1)))
        first.replace(ReplaceMemory(SCOPE, created.id, 1, "Uses Kotlin", listOf(SOURCE_2)))

        val reopened = store(path, "unused")

        val restored = reopened.get(SCOPE, created.id)
        assertEquals("Uses Kotlin", restored?.content)
        assertEquals(2L, restored?.version)
        assertEquals(listOf(SOURCE_1, SOURCE_2), restored?.sources)
    } }

    @Test
    fun `forget removes content from both reopened state and disk`() = withStorePath { path -> runBlocking {
        val store = store(path, "memory-1")
        val created = store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "sensitive-value", listOf(SOURCE_1)))

        store.forget(ForgetMemory(SCOPE, created.id, 1))

        assertNull(store(path, "unused").get(SCOPE, created.id))
        assertFalse("sensitive-value" in path.readText())
        val conflict = assertFailsWith<MemoryVersionConflictException> {
            store(path, "unused").replace(
                ReplaceMemory(SCOPE, created.id, 1, "restore", listOf(SOURCE_2)),
            )
        }
        assertEquals(2L, conflict.actualVersion)
    } }

    @Test
    fun `invalid snapshot reports its path`() = withStorePath { path ->
        path.writeText("{not-json")

        val failure = assertFailsWith<MemoryStoreFormatException> { store(path, "unused") }

        assertEquals(path, failure.path)
    }

    @Test
    fun `failed persistence does not change in-memory state`() = withStorePath { path -> runBlocking {
        val store = store(path, "memory-1")
        Files.createDirectory(path)

        assertFailsWith<Exception> {
            store.create(NewMemory(SCOPE, MemoryKind.SEMANTIC, "not persisted", listOf(SOURCE_1)))
        }

        assertNull(store.get(SCOPE, MemoryId("memory-1")))
    } }

    private fun store(path: Path, id: String): JsonFileMemoryStore = JsonFileMemoryStore(
        path = path,
        idGenerator = { MemoryId(id) },
        clock = FIXED_CLOCK,
    )

    private fun withStorePath(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("dsh-memory-")
        try {
            test(root.resolve("memory.json"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val SCOPE = MemoryScope(TenantId("tenant-1"), UserId("user-1"), AgentId("agent-1"))
        val SOURCE_1 = MemorySource(SessionId("session-1"), SessionEventRange(1, 4))
        val SOURCE_2 = MemorySource(SessionId("session-2"), SessionEventRange(5, 8))
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-24T08:00:00Z"), ZoneOffset.UTC)
    }
}
