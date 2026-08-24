package com.attuchengmen.agent.session

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** 将每个 SessionId 路由到独立且不可路径穿越的 JSONL 文件。 */
class SessionFileRepository(
    private val directory: Path,
) {
    /** 创建或重新打开指定 Session 的单文件日志。 */
    fun open(sessionId: SessionId): JsonlFileSessionLog {
        val log = JsonlFileSessionLog(pathFor(sessionId), sessionId)
        require(log.sessionId == sessionId) {
            "session file contains ${log.sessionId.value}, expected ${sessionId.value}"
        }
        return log
    }

    private fun pathFor(sessionId: SessionId): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.value.toByteArray(UTF_8))
        return directory.resolve("${HexFormat.of().formatHex(digest)}.jsonl")
    }
}
