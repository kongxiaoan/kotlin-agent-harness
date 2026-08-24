package com.attuchengmen.agent.memory

import com.attuchengmen.agent.identity.AgentId
import com.attuchengmen.agent.identity.TenantId
import com.attuchengmen.agent.identity.UserId
import com.attuchengmen.agent.session.SessionEventRange
import com.attuchengmen.agent.session.SessionId
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Memory 快照无法恢复成受支持的作用域化状态。 */
class MemoryStoreFormatException(
    val path: Path,
    cause: Throwable,
) : IllegalStateException("invalid memory snapshot at $path", cause)

/** 新 Memory 快照无法原子写入目标文件。 */
class MemoryStorePersistenceException(
    val path: Path,
    cause: Throwable,
) : IllegalStateException("cannot persist memory snapshot at $path", cause)

/**
 * 使用原子文件替换持久化当前 Memory 状态。
 *
 * forget 后的快照只保留 ID、Scope 和版本，不保留正文。本实现串行化同一实例，
 * 不协调多个进程同时写同一文件；服务端部署应替换为数据库 MemoryStore。
 */
class JsonFileMemoryStore(
    private val path: Path,
    private val idGenerator: () -> MemoryId = MemoryId::generate,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryStore {
    private val lock = Any()
    private var index = MemorySnapshotJson.load(path)

    override suspend fun create(memory: NewMemory): MemoryRecord = synchronized(lock) {
        val change = index.create(memory, idGenerator(), clock.instant())
        persist(change.index)
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
        persist(change.index)
        index = change.index
        change.result
    }

    override suspend fun forget(memory: ForgetMemory) {
        synchronized(lock) {
            val updated = index.forget(memory)
            persist(updated)
            index = updated
        }
    }

    private fun persist(updated: MemoryIndex) {
        val absolute = path.toAbsolutePath()
        val parent = checkNotNull(absolute.parent) { "memory path must have a parent directory" }
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, ".${absolute.fileName}.", ".tmp")
            Files.writeString(temporary, MemorySnapshotJson.encode(updated), UTF_8, WRITE, TRUNCATE_EXISTING)
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            Files.move(temporary, absolute, ATOMIC_MOVE, REPLACE_EXISTING)
            temporary = null
        } catch (error: Exception) {
            throw MemoryStorePersistenceException(path, error)
        } finally {
            temporary?.let { temp ->
                try {
                    Files.deleteIfExists(temp)
                } catch (error: Exception) {
                    logger.warn("Failed to delete temporary memory snapshot path={}", temp.toAbsolutePath(), error)
                }
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(JsonFileMemoryStore::class.java)
    }
}

/** 文件 DTO 与 Memory 领域状态之间的严格映射。 */
private object MemorySnapshotJson {
    private const val FORMAT_VERSION = 1
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(index: MemoryIndex): String = json.encodeToString(
        StoredMemorySnapshot(
            formatVersion = FORMAT_VERSION,
            entries = index.snapshot().map(MemoryEntry::toStored),
        ),
    )

    fun load(path: Path): MemoryIndex {
        if (Files.notExists(path)) return MemoryIndex()
        try {
            val snapshot = json.decodeFromString<StoredMemorySnapshot>(Files.readString(path, UTF_8))
            if (snapshot.formatVersion != FORMAT_VERSION) {
                throw SerializationException("unsupported memory format version ${snapshot.formatVersion}")
            }
            return MemoryIndex.restore(snapshot.entries.map(StoredMemoryEntry::toDomain))
        } catch (error: Exception) {
            throw MemoryStoreFormatException(path, error)
        }
    }
}

@Serializable
private data class StoredMemorySnapshot(
    val formatVersion: Int,
    val entries: List<StoredMemoryEntry>,
)

@Serializable
private data class StoredMemoryEntry(
    val id: String,
    val tenantId: String,
    val userId: String,
    val agentId: String,
    val version: Long,
    val active: StoredActiveMemory? = null,
)

@Serializable
private data class StoredActiveMemory(
    val kind: SnapshotMemoryKind,
    val content: String,
    val sources: List<StoredMemorySource>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class StoredMemorySource(
    val sessionId: String,
    val fromSequence: Long,
    val toSequence: Long,
)

@Serializable
private enum class SnapshotMemoryKind {
    @SerialName("semantic")
    SEMANTIC,

    @SerialName("episodic")
    EPISODIC,

    @SerialName("procedural")
    PROCEDURAL,
}

private fun MemoryEntry.toStored(): StoredMemoryEntry = StoredMemoryEntry(
    id = id.value,
    tenantId = scope.tenantId.value,
    userId = scope.userId.value,
    agentId = scope.agentId.value,
    version = version,
    active = record?.let { memory ->
        StoredActiveMemory(
            kind = memory.kind.toStored(),
            content = memory.content,
            sources = memory.sources.map { source ->
                StoredMemorySource(
                    source.sessionId.value,
                    source.eventRange.fromSequence,
                    source.eventRange.toSequence,
                )
            },
            createdAt = memory.createdAt.toString(),
            updatedAt = memory.updatedAt.toString(),
        )
    },
)

private fun StoredMemoryEntry.toDomain(): MemoryEntry {
    val memoryId = MemoryId(id)
    val memoryScope = MemoryScope(TenantId(tenantId), UserId(userId), AgentId(agentId))
    return MemoryEntry(
        id = memoryId,
        scope = memoryScope,
        version = version,
        record = active?.let { memory ->
            MemoryRecord(
                id = memoryId,
                scope = memoryScope,
                kind = memory.kind.toDomain(),
                content = memory.content,
                sources = memory.sources.map { source ->
                    MemorySource(
                        SessionId(source.sessionId),
                        SessionEventRange(source.fromSequence, source.toSequence),
                    )
                },
                version = version,
                createdAt = Instant.parse(memory.createdAt),
                updatedAt = Instant.parse(memory.updatedAt),
            )
        },
    )
}

private fun MemoryKind.toStored(): SnapshotMemoryKind = when (this) {
    MemoryKind.SEMANTIC -> SnapshotMemoryKind.SEMANTIC
    MemoryKind.EPISODIC -> SnapshotMemoryKind.EPISODIC
    MemoryKind.PROCEDURAL -> SnapshotMemoryKind.PROCEDURAL
}

private fun SnapshotMemoryKind.toDomain(): MemoryKind = when (this) {
    SnapshotMemoryKind.SEMANTIC -> MemoryKind.SEMANTIC
    SnapshotMemoryKind.EPISODIC -> MemoryKind.EPISODIC
    SnapshotMemoryKind.PROCEDURAL -> MemoryKind.PROCEDURAL
}
