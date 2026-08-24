package com.attuchengmen.agent.memory

import java.time.Clock
import java.util.Locale

/** 请求访问的记忆不存在、已遗忘或不属于当前 Scope。 */
class MemoryNotFoundException(
    val memoryId: MemoryId,
) : NoSuchElementException("memory ${memoryId.value} does not exist")

/** 更新者持有的记忆版本已经过期。 */
class MemoryVersionConflictException(
    val memoryId: MemoryId,
    val expectedVersion: Long,
    val actualVersion: Long,
) : IllegalStateException(
    "memory ${memoryId.value} expected version $expectedVersion but current version is $actualVersion",
)

/** 带强制 Scope、来源和乐观并发语义的长期记忆存储边界。 */
interface MemoryStore {
    suspend fun create(memory: NewMemory): MemoryRecord

    suspend fun get(scope: MemoryScope, id: MemoryId): MemoryRecord?

    suspend fun search(query: MemoryQuery): List<MemoryRecord>

    suspend fun replace(memory: ReplaceMemory): MemoryRecord

    suspend fun forget(memory: ForgetMemory)
}

/** 进程内 MemoryStore，为后续持久化实现提供参考语义。 */
class InMemoryMemoryStore(
    private val idGenerator: () -> MemoryId = MemoryId::generate,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryStore {
    private val lock = Any()
    private var index = MemoryIndex()

    override suspend fun create(memory: NewMemory): MemoryRecord = synchronized(lock) {
        val change = index.create(memory, idGenerator(), clock.instant())
        index = change.index
        change.result
    }

    override suspend fun get(scope: MemoryScope, id: MemoryId): MemoryRecord? = synchronized(lock) {
        index.get(scope, id)
    }

    override suspend fun search(query: MemoryQuery): List<MemoryRecord> = synchronized(lock) {
        index.search(query)
    }

    override suspend fun replace(memory: ReplaceMemory): MemoryRecord = synchronized(lock) {
        val change = index.replace(memory, clock.instant())
        index = change.index
        change.result
    }

    override suspend fun forget(memory: ForgetMemory) {
        synchronized(lock) {
            index = index.forget(memory)
        }
    }
}

/** MemoryStore 实现共享的不可变当前状态及状态转换规则。 */
internal class MemoryIndex private constructor(
    private val entries: Map<MemoryId, MemoryEntry>,
) {
    constructor() : this(emptyMap())

    fun create(memory: NewMemory, id: MemoryId, now: java.time.Instant): MemoryChange<MemoryRecord> {
        check(id !in entries) { "memory id ${id.value} already exists" }
        val record = MemoryRecord(
            id = id,
            scope = memory.scope,
            kind = memory.kind,
            content = memory.content,
            sources = memory.sources.toList(),
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
        return MemoryChange(withEntry(MemoryEntry(id, memory.scope, record.version, record)), record)
    }

    fun get(scope: MemoryScope, id: MemoryId): MemoryRecord? =
        entries[id]?.takeIf { it.scope == scope }?.record

    fun search(query: MemoryQuery): List<MemoryRecord> {
        val normalizedQuery = query.text.lowercase(Locale.ROOT)
        return entries.values.asSequence()
            .filter { it.scope == query.scope }
            .mapNotNull(MemoryEntry::record)
            .filter { it.kind in query.kinds && normalizedQuery in it.content.lowercase(Locale.ROOT) }
            .sortedWith(compareByDescending<MemoryRecord> { it.updatedAt }.thenBy { it.id.value })
            .take(query.limit)
            .toList()
    }

    fun replace(memory: ReplaceMemory, now: java.time.Instant): MemoryChange<MemoryRecord> {
        val stored = visibleMemory(memory.scope, memory.id)
        validateVersion(memory.id, memory.expectedVersion, stored.version)
        val current = stored.record ?: throw MemoryNotFoundException(memory.id)
        val updated = current.copy(
            content = memory.content,
            sources = (current.sources + memory.sources).distinct(),
            version = current.version + 1,
            updatedAt = maxOf(now, current.updatedAt),
        )
        return MemoryChange(withEntry(MemoryEntry(memory.id, memory.scope, updated.version, updated)), updated)
    }

    fun forget(memory: ForgetMemory): MemoryIndex {
        val stored = visibleMemory(memory.scope, memory.id)
        validateVersion(memory.id, memory.expectedVersion, stored.version)
        if (stored.record == null) throw MemoryNotFoundException(memory.id)
        return withEntry(MemoryEntry(memory.id, memory.scope, stored.version + 1, record = null))
    }

    fun snapshot(): List<MemoryEntry> = entries.values.sortedBy { it.id.value }

    private fun withEntry(entry: MemoryEntry): MemoryIndex = MemoryIndex(entries + (entry.id to entry))

    private fun visibleMemory(scope: MemoryScope, id: MemoryId): MemoryEntry =
        entries[id]?.takeIf { it.scope == scope } ?: throw MemoryNotFoundException(id)

    private fun validateVersion(id: MemoryId, expected: Long, actual: Long) {
        if (expected != actual) throw MemoryVersionConflictException(id, expected, actual)
    }

    companion object {
        fun restore(entries: List<MemoryEntry>): MemoryIndex {
            require(entries.map(MemoryEntry::id).distinct().size == entries.size) {
                "memory snapshot contains duplicate ids"
            }
            entries.forEach { entry ->
                require(entry.version > 0) { "stored memory version must be positive" }
                entry.record?.let { record ->
                    require(record.id == entry.id && record.scope == entry.scope && record.version == entry.version) {
                        "stored memory metadata does not match its record"
                    }
                } ?: require(entry.version > 1) { "forgotten memory version must exceed one" }
            }
            return MemoryIndex(entries.associateBy(MemoryEntry::id))
        }
    }
}

internal data class MemoryEntry(
    val id: MemoryId,
    val scope: MemoryScope,
    val version: Long,
    val record: MemoryRecord?,
)

internal data class MemoryChange<T>(
    val index: MemoryIndex,
    val result: T,
)
