package com.attuchengmen.agent.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionFileRepositoryTest {
    @Test
    fun `different sessions persist to independent files and reopen by id`() {
        val directory = Files.createTempDirectory("dsh-sessions-")
        try {
            val repository = SessionFileRepository(directory)
            val firstId = SessionId("session-1")
            val secondId = SessionId("session-2")
            Session(repository.open(firstId)).append(TurnStarted(1))
            Session(repository.open(secondId)).append(TurnStarted(1))

            assertEquals(listOf(TurnStarted(1)), Session(repository.open(firstId)).events.take(1))
            assertEquals(listOf(TurnStarted(1)), Session(repository.open(secondId)).events.take(1))
            assertEquals(2, Files.list(directory).use { it.count() })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `session id cannot control the file path`() {
        val root = Files.createTempDirectory("dsh-sessions-")
        try {
            val directory = root.resolve("sessions")
            val repository = SessionFileRepository(directory)
            Session(repository.open(SessionId("../../outside"))).append(TurnStarted(1))

            assertEquals(1, Files.list(directory).use { it.count() })
            assertFalse(Files.exists(root.resolve("outside.jsonl")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
