package com.attuchengmen.agent.session

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonlFileSessionLogTest {
    @Test
    fun `events are appended as json lines and restored in order`() = withTempLog { path ->
        val session = Session(JsonlFileSessionLog(path))
        val events = listOf(
            TurnStarted(turn = 1),
            UserMessageAdded("hello"),
            AssistantMessageAdded("hi"),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
        )

        events.forEach(session::append)

        assertEquals(events.size, path.readLines().size)
        assertEquals(events, Session(JsonlFileSessionLog(path)).events)
    }

    @Test
    fun `invalid json line reports its exact location`() = withTempLog { path ->
        path.writeText("{\"type\":\"unknown-event\"}\n")

        val failure = assertFailsWith<SessionLogFormatException> {
            JsonlFileSessionLog(path)
        }

        assertEquals(path, failure.path)
        assertEquals(1, failure.lineNumber)
    }

    private fun withTempLog(test: (Path) -> Unit) {
        val path = Files.createTempFile("dsh-session-", ".jsonl")
        try {
            Files.deleteIfExists(path)
            test(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
