package com.attuchengmen.agent.session

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionEventStoreTest {
    private val occurredAt = Instant.parse("2026-08-20T08:00:00Z")

    @Test
    fun `sessions are isolated and can be read after a cursor`() {
        val store = InMemorySessionEventStore()
        val firstSession = SessionId("session-1")
        val secondSession = SessionId("session-2")
        store.createSession(firstSession)
        store.createSession(secondSession)

        store.append(firstSession, expectedSequence = 0, envelope = envelope(firstSession, 1, TurnStarted(1)))
        store.append(firstSession, expectedSequence = 1, envelope = envelope(firstSession, 2, UserMessageAdded("hello")))
        store.append(secondSession, expectedSequence = 0, envelope = envelope(secondSession, 1, TurnStarted(1)))

        assertEquals(listOf(2L), store.read(firstSession, afterSequence = 1).map { it.sequence })
        assertEquals(listOf(1L), store.read(secondSession).map { it.sequence })
    }

    @Test
    fun `duplicate create and access to a missing session fail explicitly`() {
        val store = InMemorySessionEventStore()
        val existing = SessionId("session-1")
        val missing = SessionId("missing")
        store.createSession(existing)

        assertFailsWith<StoredSessionAlreadyExistsException> { store.createSession(existing) }
        assertFailsWith<StoredSessionNotFoundException> { store.read(missing) }
        assertFailsWith<StoredSessionNotFoundException> {
            store.append(missing, expectedSequence = 0, envelope = envelope(missing, 1, TurnStarted(1)))
        }
    }

    @Test
    fun `stale expected sequence is rejected without changing stored events`() {
        val store = InMemorySessionEventStore()
        val sessionId = SessionId("session-1")
        store.createSession(sessionId)
        store.append(sessionId, expectedSequence = 0, envelope = envelope(sessionId, 1, TurnStarted(1)))

        val failure = assertFailsWith<SessionSequenceConflictException> {
            store.append(
                sessionId,
                expectedSequence = 0,
                envelope(sessionId, 2, UserMessageAdded("stale writer")),
            )
        }

        assertEquals(0, failure.expectedSequence)
        assertEquals(1, failure.actualSequence)
        assertEquals(listOf(1L), store.read(sessionId).map { it.sequence })
    }

    @Test
    fun `append rejects an envelope for another session or a noncontiguous sequence`() {
        val store = InMemorySessionEventStore()
        val sessionId = SessionId("session-1")
        store.createSession(sessionId)

        assertFailsWith<IllegalArgumentException> {
            store.append(
                sessionId,
                expectedSequence = 0,
                envelope(SessionId("session-2"), 1, TurnStarted(1)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.append(sessionId, expectedSequence = 0, envelope = envelope(sessionId, 2, TurnStarted(1)))
        }
        assertTrue(store.read(sessionId).isEmpty())
    }

    @Test
    fun `event store session logs share durable state and return stable snapshots`() {
        val store = InMemorySessionEventStore()
        val sessionId = SessionId("session-1")
        val firstLog = EventStoreSessionLog.create(store, sessionId)
        val session = Session(firstLog, Clock.fixed(occurredAt, ZoneOffset.UTC))

        session.append(UserMessageAdded("hello"))
        val snapshot = firstLog.envelopes
        val reopened = Session(EventStoreSessionLog.open(store, sessionId), Clock.fixed(occurredAt, ZoneOffset.UTC))
        reopened.append(AssistantMessageAdded("hi"))

        assertEquals(listOf(1L), snapshot.map { it.sequence })
        assertEquals(listOf(1L, 2L), firstLog.envelopes.map { it.sequence })
        assertEquals(listOf(UserMessageAdded("hello"), AssistantMessageAdded("hi")), reopened.events)
    }

    private fun envelope(
        sessionId: SessionId,
        sequence: Long,
        event: SessionEvent,
    ): SessionEventEnvelope = SessionEventEnvelope(sessionId, sequence, occurredAt, event)
}
