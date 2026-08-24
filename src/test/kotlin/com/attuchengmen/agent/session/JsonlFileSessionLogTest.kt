package com.attuchengmen.agent.session

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonlFileSessionLogTest {
    @Test
    fun `events are appended as json lines and restored in order`() = withTempLog { path ->
        val sessionId = SessionId("session-1")
        val occurredAt = Instant.parse("2026-08-20T08:00:00Z")
        val session = Session(
            JsonlFileSessionLog(path, sessionId),
            Clock.fixed(occurredAt, ZoneOffset.UTC),
        )
        val events = listOf(
            TurnStarted(turn = 1),
            UserMessageAdded("hello"),
            AssistantMessageAdded("hi"),
            TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
        )

        events.forEach(session::append)

        assertEquals(events.size, path.readLines().size)
        val restored = Session(JsonlFileSessionLog(path, SessionId("ignored-for-existing-log")))
        assertEquals(sessionId, restored.id)
        assertEquals(session.envelopes, restored.envelopes)
        assertEquals(events, restored.events)
    }

    @Test
    fun `mixed session id reports the offending line`() = withTempLog { path ->
        val occurredAt = Instant.parse("2026-08-20T08:00:00Z")
        path.writeText(
            listOf(
                SessionEventJson.encode(
                    SessionEventEnvelope(SessionId("session-1"), 1, occurredAt, TurnStarted(1)),
                ),
                SessionEventJson.encode(
                    SessionEventEnvelope(SessionId("session-2"), 2, occurredAt, UserMessageAdded("wrong")),
                ),
            ).joinToString(separator = "\n", postfix = "\n"),
        )

        val failure = assertFailsWith<SessionLogFormatException> { JsonlFileSessionLog(path) }

        assertEquals(2, failure.lineNumber)
    }

    @Test
    fun `noncontiguous sequence reports the offending line`() = withTempLog { path ->
        val envelope = SessionEventEnvelope(
            SessionId("session-1"),
            sequence = 2,
            occurredAt = Instant.parse("2026-08-20T08:00:00Z"),
            event = TurnStarted(1),
        )
        path.writeText("${SessionEventJson.encode(envelope)}\n")

        val failure = assertFailsWith<SessionLogFormatException> { JsonlFileSessionLog(path) }

        assertEquals(1, failure.lineNumber)
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

    @Test
    fun `legacy event without envelope is rejected`() = withTempLog { path ->
        path.writeText("""{"type":"turn-started","turn":1}
""")

        val failure = assertFailsWith<SessionLogFormatException> { JsonlFileSessionLog(path) }

        assertEquals(1, failure.lineNumber)
    }

    @Test
    fun `stale expected sequence does not append a json line`() = withTempLog { path ->
        val sessionId = SessionId("session-1")
        val occurredAt = Instant.parse("2026-08-20T08:00:00Z")
        val log = JsonlFileSessionLog(path, sessionId)
        log.append(0, SessionEventEnvelope(sessionId, 1, occurredAt, TurnStarted(1)))

        assertFailsWith<SessionSequenceConflictException> {
            log.append(0, SessionEventEnvelope(sessionId, 2, occurredAt, UserMessageAdded("stale")))
        }

        assertEquals(1, path.readLines().size)
        assertEquals(listOf(1L), log.envelopes.map { it.sequence })
    }

    @Test
    fun `reopening repairs and persists an interrupted turn`() = withTempLog { path ->
        val original = Session(JsonlFileSessionLog(path))
        original.append(TurnStarted(turn = 1))
        original.append(StepStarted(turn = 1, step = 1))

        val recovered = Session(JsonlFileSessionLog(path))

        assertEquals(
            listOf(
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.Interrupted),
            ),
            recovered.events,
        )
        assertEquals(recovered.events.size, path.readLines().size)
        assertEquals(recovered.events, Session(JsonlFileSessionLog(path)).events)
        assertEquals(listOf(1L, 2L, 3L, 4L), recovered.envelopes.map { it.sequence })
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
