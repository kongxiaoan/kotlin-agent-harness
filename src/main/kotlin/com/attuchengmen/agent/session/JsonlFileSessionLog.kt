package com.attuchengmen.agent.session

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Session JSONL 中某一行无法恢复成受支持事件信封。 */
class SessionLogFormatException(
    val path: Path,
    val lineNumber: Int,
    cause: Throwable,
) : IllegalStateException("invalid session event at $path:$lineNumber", cause)

/**
 * 每行保存一个完整 SessionEventEnvelope 的文件日志。
 *
 * 构造时加载并验证已有日志；追加先写入文件，成功后才更新内存快照。
 * 本实现保证同一实例内的调用串行，不协调多个进程同时写同一文件。
 */
class JsonlFileSessionLog(
    private val path: Path,
    newSessionId: SessionId = SessionId.generate(),
) : SessionLog {
    private val lock = Any()
    private val eventLog = load(path).toMutableList()
    override val sessionId: SessionId = eventLog.firstOrNull()?.sessionId ?: newSessionId

    init {
        logger.info(
            "Opened session log path={} sessionId={} events={}",
            path.toAbsolutePath(),
            sessionId.value,
            eventLog.size,
        )
    }

    override val envelopes: List<SessionEventEnvelope>
        get() = synchronized(lock) { eventLog.toList() }

    override fun append(expectedSequence: Long, envelope: SessionEventEnvelope) {
        val encoded = SessionEventJson.encode(envelope)
        synchronized(lock) {
            validateExpectedSequence(sessionId, expectedSequence, eventLog.lastOrNull()?.sequence ?: 0)
            validateNextEnvelope(sessionId, eventLog.lastOrNull()?.sequence, envelope)
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(path, "$encoded\n", UTF_8, CREATE, WRITE, APPEND)
            eventLog.add(envelope)
        }
        logger.debug(
            "Appended session event type={} path={} sequence={}",
            envelope.event::class.simpleName,
            path.toAbsolutePath(),
            envelope.sequence,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(JsonlFileSessionLog::class.java)

        private fun load(path: Path): List<SessionEventEnvelope> {
            if (Files.notExists(path)) return emptyList()
            val envelopes = Files.newBufferedReader(path, UTF_8).use { reader ->
                reader.lineSequence().mapIndexed { index, line ->
                    try {
                        SessionEventJson.decode(line)
                    } catch (error: Exception) {
                        throw SessionLogFormatException(path, index + 1, error)
                    }
                }.toList()
            }
            envelopes.forEachIndexed { index, envelope ->
                try {
                    validateNextEnvelope(
                        sessionId = envelopes.first().sessionId,
                        previousSequence = envelopes.getOrNull(index - 1)?.sequence,
                        envelope = envelope,
                    )
                } catch (error: IllegalArgumentException) {
                    throw SessionLogFormatException(path, index + 1, error)
                }
            }
            return envelopes
        }
    }
}
