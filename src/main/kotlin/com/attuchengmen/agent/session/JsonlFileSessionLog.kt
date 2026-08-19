package com.attuchengmen.agent.session

import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Session JSONL 中某一行无法恢复成受支持事件。 */
class SessionLogFormatException(
    val path: Path,
    val lineNumber: Int,
    cause: Throwable,
) : IllegalStateException("invalid session event at $path:$lineNumber", cause)

/**
 * 每行保存一个完整 SessionEvent 的文件日志。
 *
 * 构造时加载并验证已有日志；追加先写入文件，成功后才更新内存快照。
 * 本实现保证同一实例内的调用串行，不协调多个进程同时写同一文件。
 */
class JsonlFileSessionLog(
    private val path: Path,
) : SessionLog {
    private val lock = Any()
    private val eventLog = load(path).toMutableList()

    init {
        logger.info("Opened session log path={} events={}", path.toAbsolutePath(), eventLog.size)
    }

    override val events: List<SessionEvent>
        get() = synchronized(lock) { eventLog.toList() }

    override fun append(event: SessionEvent) {
        val encoded = SessionEventJson.encode(event)
        val count = synchronized(lock) {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(path, "$encoded\n", UTF_8, CREATE, WRITE, APPEND)
            eventLog.add(event)
            eventLog.size
        }
        logger.debug(
            "Appended session event type={} path={} count={}",
            event::class.simpleName,
            path.toAbsolutePath(),
            count,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(JsonlFileSessionLog::class.java)

        private fun load(path: Path): List<SessionEvent> {
            if (Files.notExists(path)) return emptyList()
            return Files.newBufferedReader(path, UTF_8).use { reader ->
                reader.lineSequence().mapIndexed { index, line ->
                    try {
                        SessionEventJson.decode(line)
                    } catch (error: SerializationException) {
                        throw SessionLogFormatException(path, index + 1, error)
                    }
                }.toList()
            }
        }
    }
}
